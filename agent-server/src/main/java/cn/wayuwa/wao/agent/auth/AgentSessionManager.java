package cn.wayuwa.wao.agent.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 从与 WAO 共享的 Spring Session 中读取当前账号。 */
@Component
public class AgentSessionManager {
    private static final String ACCOUNT_ID = "wao.accountId";
    private final SharedSessionProperties properties;

    public AgentSessionManager(SharedSessionProperties properties) {
        this.properties = properties;
    }

    /** 返回 WAO 当前账号；local profile 可用显式 Demo 账号补位。 */
    public String currentAccountId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        String accountId = session == null ? null : (String) session.getAttribute(ACCOUNT_ID);
        if (StringUtils.hasText(accountId)) return accountId;
        return StringUtils.hasText(properties.localAccountId()) ? properties.localAccountId() : null;
    }
}
