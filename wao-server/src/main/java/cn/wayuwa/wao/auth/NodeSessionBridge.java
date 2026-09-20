package cn.wayuwa.wao.auth;

import cn.wayuwa.wao.common.ForbiddenException;
import cn.wayuwa.wao.common.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.stereotype.Service;

/** 用一次性票据创建节点会话，每次认证仍核对MySQL中的主站权威会话。 */
@Service
@ConditionalOnProperty(name = "wao.edge.session-enabled", havingValue = "true")
public class NodeSessionBridge {
    public static final String ROOT_SESSION = "wao.edgeRootSessionId";
    static final String TARGET_ORIGIN = "wao.edgeTargetOrigin";
    static final String ACCOUNT = "wao.accountId";
    static final String NODE_ACCOUNT = "wao.edgeAccountId";
    static final String CSRF = "wao.edgeCsrf";
    static final String MAIN_ORIGIN = "https://wao.wayuwa.cn";
    static final Set<String> NODE_ORIGINS = Set.of("https://linux1.wayuwa.cn:9443", "https://linux2.wayuwa.cn:9443");
    private final NodeTicketStore tickets;
    private final SessionRepository<? extends Session> authority;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public record TicketGrant(String ticket, long expiresAt) {}

    @Autowired
    public NodeSessionBridge(NodeTicketStore tickets, JdbcIndexedSessionRepository authority) {
        this(tickets, authority, Clock.systemUTC());
    }

    NodeSessionBridge(NodeTicketStore tickets, SessionRepository<? extends Session> authority, Clock clock) {
        this.tickets = tickets;
        this.authority = authority;
        this.clock = clock;
    }

    /** 只在主站已认证会话内生成CSRF令牌，不创建匿名会话。 */
    public String csrf(HttpServletRequest request) {
        HttpSession session = requireMainSession(request);
        synchronized (session) {
            String token = (String) session.getAttribute(CSRF);
            if (token == null) { token = randomToken(); session.setAttribute(CSRF, token); }
            return token;
        }
    }

    /** 校验主站Origin及CSRF后，签发绑定账号、会话和目标节点的六十秒票据。 */
    public TicketGrant issue(HttpServletRequest request, String targetOrigin) {
        requireMainOrigin(request);
        HttpSession session = requireMainSession(request);
        requireCsrf(request, (String) session.getAttribute(CSRF));
        if (!NODE_ORIGINS.contains(targetOrigin)) throw new ForbiddenException("不支持的节点地址");
        long now = clock.millis();
        if (tickets.recentCount(session.getId(), now - 60_000) >= 20) throw new ForbiddenException("节点认证请求过于频繁，请稍后重试");
        String token = randomToken();
        tickets.insert(hash(token), new NodeTicketStore.Ticket(session.getId(), (String) session.getAttribute(ACCOUNT), targetOrigin), now, now + 60_000);
        return new TicketGrant(token, now + 60_000);
    }

    /** 原子兑换票据并轮换节点会话；原主站Session ID从不返回浏览器。 */
    public void exchange(HttpServletRequest request, String token) {
        requireMainOrigin(request);
        String target = nodeOrigin(request);
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw expired();
        NodeTicketStore.Ticket ticket = tickets.consume(hash(token), target, clock.millis());
        if (ticket == null) throw expired();
        Session root = validRoot(ticket.rootSessionId(), ticket.accountId());
        if (root == null) throw expired();
        HttpSession previous = request.getSession(false);
        // Never exchange on the main host or invalidate the authority itself.
        if (previous != null) {
            if (previous.getId().equals(ticket.rootSessionId())) throw new ForbiddenException("不能覆盖主站会话");
            previous.invalidate();
        }
        HttpSession node = request.getSession(true);
        // Deliberately omit wao.accountId: older services (including Agent) must
        // never accept a derived session without validating its authority.
        node.setAttribute(NODE_ACCOUNT, ticket.accountId());
        node.setAttribute(ROOT_SESSION, ticket.rootSessionId());
        node.setAttribute(TARGET_ORIGIN, target);
        node.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, ticket.accountId());
    }

    /** 校验绑定的节点、主站Origin和权威会话；写操作另需权威会话的CSRF令牌。 */
    public String currentAccount(HttpServletRequest request, HttpSession node) {
        requireMainOrigin(request);
        if (!nodeOrigin(request).equals(node.getAttribute(TARGET_ORIGIN))) throw new ForbiddenException("节点会话不匹配");
        String account = (String) node.getAttribute(NODE_ACCOUNT);
        Session root = validRoot((String) node.getAttribute(ROOT_SESSION), account);
        if (root == null) { node.invalidate(); return null; }
        if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) requireCsrf(request, root.getAttribute(CSRF));
        return account;
    }

    /** 从节点注销时同时撤销主站权威会话，其余节点在下一次请求立即失效。 */
    public void logout(HttpServletRequest request, HttpSession node) {
        String rootId = (String) node.getAttribute(ROOT_SESSION);
        if (currentAccount(request, node) == null) return;
        authority.deleteById(rootId);
        node.invalidate();
    }

    private HttpSession requireMainSession(HttpServletRequest request) {
        if (!"wao.wayuwa.cn".equals(request.getServerName()) || !"https".equals(request.getScheme())) throw new ForbiddenException("请从主站发起认证");
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(ACCOUNT) == null || session.getAttribute(ROOT_SESSION) != null) throw expired();
        return session;
    }

    private Session validRoot(String id, String account) {
        if (id == null || account == null) return null;
        Session root = authority.findById(id);
        return root != null && !root.isExpired() && root.getAttribute(ROOT_SESSION) == null
                && account.equals(root.getAttribute(ACCOUNT)) ? root : null;
    }

    private String nodeOrigin(HttpServletRequest request) {
        String origin = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
        if (!NODE_ORIGINS.contains(origin)) throw new ForbiddenException("不支持的节点入口");
        return origin;
    }

    private void requireMainOrigin(HttpServletRequest request) {
        if (!MAIN_ORIGIN.equals(request.getHeader("Origin"))) throw new ForbiddenException("请求来源不受信任");
    }

    private void requireCsrf(HttpServletRequest request, String expected) {
        String actual = request.getHeader("X-WAO-CSRF");
        if (expected == null || actual == null || actual.length() > 128
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            throw new ForbiddenException("请求校验已过期，请重新连接");
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private UnauthorizedException expired() { return new UnauthorizedException("节点认证已过期，请重新连接"); }
}
