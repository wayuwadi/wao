package cn.wayuwa.wao.agent.auth;

import cn.wayuwa.wao.agent.common.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Agent 业务 API 会话保护。 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final AgentSessionManager sessions;

    public AuthInterceptor(AgentSessionManager sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getRequestURI().equals("/api/agent/health")) return true;
        if (sessions.currentAccountId(request) == null) {
            throw new UnauthorizedException("WAO 登录已过期，请重新登录后进入 Agent");
        }
        return true;
    }
}
