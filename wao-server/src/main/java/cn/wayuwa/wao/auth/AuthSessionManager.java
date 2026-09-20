package cn.wayuwa.wao.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;

/** 只在服务端 HttpSession 中读写当前账号和待认领第三方身份。 */
@Component
public class AuthSessionManager {
    private final ObjectProvider<NodeSessionBridge> bridge;
    public AuthSessionManager(ObjectProvider<NodeSessionBridge> bridge) { this.bridge = bridge; }
    private static final String ACCOUNT_ID = "wao.accountId";
    private static final String PENDING_IDENTITY = "wao.pendingIdentity";

    /** 返回当前账号 id；未登录时返回 null。 */
    public String currentAccountId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(NodeSessionBridge.ROOT_SESSION) != null) {
            NodeSessionBridge available = bridge.getIfAvailable();
            return available == null ? null : available.currentAccount(request, session);
        }
        return session == null ? null : (String) session.getAttribute(ACCOUNT_ID);
    }

    /** 轮换会话并写入已验证账号，防止会话固定攻击。 */
    public void login(HttpServletRequest request, String accountId) {
        HttpSession old = request.getSession(false);
        if (old != null) old.invalidate();
        HttpSession session = request.getSession(true);
        session.setAttribute(ACCOUNT_ID, accountId);
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, accountId);
    }

    /** 保存本次 OAuth 已验证但尚未认领的身份。 */
    public void pending(HttpServletRequest request, ExternalIdentityProfile profile) {
        request.getSession(true).setAttribute(PENDING_IDENTITY, profile);
    }

    /** 取出并一次性删除待认领身份。 */
    public ExternalIdentityProfile consumePending(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        ExternalIdentityProfile profile = (ExternalIdentityProfile) session.getAttribute(PENDING_IDENTITY);
        session.removeAttribute(PENDING_IDENTITY);
        return profile;
    }

    /** 注销并删除当前服务端会话。 */
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(NodeSessionBridge.ROOT_SESSION) != null) {
            NodeSessionBridge available = bridge.getIfAvailable();
            if (available != null) available.logout(request, session);
            else session.invalidate();
            return;
        }
        if (session != null) session.invalidate();
    }
}
