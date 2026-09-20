package cn.wayuwa.wao.auth;

import static org.junit.jupiter.api.Assertions.*;
import cn.wayuwa.wao.common.ForbiddenException;
import cn.wayuwa.wao.common.UnauthorizedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;

class NodeSessionBridgeTest {
    private final MemoryTickets tickets = new MemoryTickets();
    private final MapSessionRepository authority = new MapSessionRepository(new ConcurrentHashMap<>());
    private final MutableClock clock = new MutableClock();
    private final NodeSessionBridge bridge = new NodeSessionBridge(tickets, authority, clock);
    private MockHttpServletRequest main;
    private String csrf;

    @BeforeEach void setUp() {
        main = request("wao.wayuwa.cn", 443, "GET");
        var session = new MockHttpSession(null, UUID.randomUUID().toString());
        session.setAttribute(NodeSessionBridge.ACCOUNT, "account-one");
        main.setSession(session);
        csrf = bridge.csrf(main);
        var root = new MapSession(session.getId());
        root.setAttribute(NodeSessionBridge.ACCOUNT, "account-one");
        root.setAttribute(NodeSessionBridge.CSRF, csrf);
        authority.save(root);
        main.setMethod("POST");
        main.addHeader("X-WAO-CSRF", csrf);
    }

    private MockHttpServletRequest request(String host, int port, String method) {
        var request = new MockHttpServletRequest(method, "/api/edge/exchange");
        request.setScheme("https"); request.setServerName(host); request.setServerPort(port);
        request.addHeader("Origin", NodeSessionBridge.MAIN_ORIGIN);
        return request;
    }

    private MockHttpServletRequest bind(String host) {
        var grant = bridge.issue(main, "https://" + host + ":9443");
        var request = request(host, 9443, "POST");
        bridge.exchange(request, grant.ticket());
        return request;
    }

    @Test void ticketIsOpaqueHashedShortLivedAndCannotReplay() {
        var grant = bridge.issue(main, "https://linux1.wayuwa.cn:9443");
        assertEquals(43, grant.ticket().length());
        assertNotEquals(main.getSession().getId(), grant.ticket());
        assertEquals(clock.millis() + 60_000, grant.expiresAt());
        assertFalse(tickets.values.containsKey(grant.ticket()));
        var node = request("linux1.wayuwa.cn", 9443, "POST");
        bridge.exchange(node, grant.ticket());
        assertEquals(main.getSession().getId(), node.getSession().getAttribute(NodeSessionBridge.ROOT_SESSION));
        assertNotEquals(main.getSession().getId(), node.getSession().getId());
        assertNull(node.getSession().getAttribute(NodeSessionBridge.ACCOUNT));
        assertThrows(UnauthorizedException.class, () -> bridge.exchange(node, grant.ticket()));
        node.setMethod("GET");
        assertEquals("account-one", bridge.currentAccount(node, node.getSession()));
    }

    @Test void wrongTargetExpiryAndLogoutRejectExchange() {
        var grant = bridge.issue(main, "https://linux1.wayuwa.cn:9443");
        assertThrows(UnauthorizedException.class, () -> bridge.exchange(request("linux2.wayuwa.cn", 9443, "POST"), grant.ticket()));
        clock.now = clock.now.plusSeconds(60);
        assertThrows(UnauthorizedException.class, () -> bridge.exchange(request("linux1.wayuwa.cn", 9443, "POST"), grant.ticket()));
        var next = bridge.issue(main, "https://linux1.wayuwa.cn:9443");
        authority.deleteById(main.getSession().getId());
        assertThrows(UnauthorizedException.class, () -> bridge.exchange(request("linux1.wayuwa.cn", 9443, "POST"), next.ticket()));
    }

    @Test void originAndCsrfAreRequiredAndUnknownTargetsAreRejected() {
        main.removeHeader("Origin");
        assertThrows(ForbiddenException.class, () -> bridge.issue(main, "https://linux1.wayuwa.cn:9443"));
        main.addHeader("Origin", "https://untrusted.wayuwa.cn");
        assertThrows(ForbiddenException.class, () -> bridge.issue(main, "https://linux1.wayuwa.cn:9443"));
        main.removeHeader("Origin"); main.addHeader("Origin", NodeSessionBridge.MAIN_ORIGIN);
        main.removeHeader("X-WAO-CSRF");
        assertThrows(ForbiddenException.class, () -> bridge.issue(main, "https://linux1.wayuwa.cn:9443"));
        main.addHeader("X-WAO-CSRF", csrf);
        assertThrows(ForbiddenException.class, () -> bridge.issue(main, "https://attacker.invalid"));
        var grant = bridge.issue(main, "https://linux1.wayuwa.cn:9443");
        var bad = request("linux1.wayuwa.cn", 9443, "POST"); bad.removeHeader("Origin");
        assertThrows(ForbiddenException.class, () -> bridge.exchange(bad, grant.ticket()));
        bad.addHeader("Origin", NodeSessionBridge.MAIN_ORIGIN);
        bridge.exchange(bad, grant.ticket());
    }

    @Test void boundWritesNeedCsrfAndRevocationInvalidatesEveryNode() {
        var one = bind("linux1.wayuwa.cn");
        var two = bind("linux2.wayuwa.cn");
        assertThrows(ForbiddenException.class, () -> bridge.currentAccount(one, one.getSession()));
        one.addHeader("X-WAO-CSRF", csrf);
        assertEquals("account-one", bridge.currentAccount(one, one.getSession()));
        bridge.logout(one, one.getSession());
        assertNull(authority.findById(main.getSession().getId()));
        two.setMethod("GET");
        assertNull(bridge.currentAccount(two, two.getSession()));
    }

    @Test void copiedBindingCannotChangeAccountOrTarget() {
        var node = bind("linux1.wayuwa.cn"); node.setMethod("GET");
        node.setServerName("linux2.wayuwa.cn");
        assertThrows(ForbiddenException.class, () -> bridge.currentAccount(node, node.getSession()));
        node.setServerName("linux1.wayuwa.cn");
        node.getSession().setAttribute(NodeSessionBridge.NODE_ACCOUNT, "another-account");
        assertNull(bridge.currentAccount(node, node.getSession()));
    }

    @Test void nodeCannotIssueTicketsOrReplaceAuthorityAndParallelRedeemHasOneWinner() throws Exception {
        var node = bind("linux1.wayuwa.cn");
        assertThrows(ForbiddenException.class, () -> bridge.csrf(node));
        var grant = bridge.issue(main, "https://linux1.wayuwa.cn:9443");
        var same = request("linux1.wayuwa.cn", 9443, "POST"); same.setSession((MockHttpSession) main.getSession());
        assertThrows(ForbiddenException.class, () -> bridge.exchange(same, grant.ticket()));
        assertNotNull(main.getSession().getAttribute(NodeSessionBridge.ACCOUNT));
        var concurrent = bridge.issue(main, "https://linux1.wayuwa.cn:9443");
        var wins = new java.util.concurrent.atomic.AtomicInteger();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var jobs = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            for (int i = 0; i < 8; i++) jobs.add(() -> {
                try { bridge.exchange(request("linux1.wayuwa.cn", 9443, "POST"), concurrent.ticket()); wins.incrementAndGet(); }
                catch (UnauthorizedException expected) { }
                return null;
            });
            for (var future : pool.invokeAll(jobs)) future.get();
            assertEquals(1, wins.get());
        } finally { pool.shutdownNow(); }
    }

    static class MutableClock extends Clock {
        Instant now = Instant.now();
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    static class MemoryTickets implements NodeTicketStore {
        record Stored(Ticket ticket, long expires) {}
        final ConcurrentHashMap<String, Stored> values = new ConcurrentHashMap<>();
        public void insert(String hash, Ticket ticket, long created, long expires) { values.put(hash, new Stored(ticket, expires)); }
        public int recentCount(String id, long since) { return 0; }
        public Ticket consume(String hash, String target, long now) {
            var result = new AtomicReference<Ticket>();
            values.computeIfPresent(hash, (key, stored) -> {
                if (stored.expires() <= now || !stored.ticket().targetOrigin().equals(target)) return stored;
                result.set(stored.ticket()); return null;
            });
            return result.get();
        }
    }
}
