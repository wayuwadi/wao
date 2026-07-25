package cn.wayuwa.wao.auth;

import cn.wayuwa.wao.common.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.servlet.HandlerInterceptor;

/** 为业务 API 强制校验可信服务端会话。 */
@Component
@ConditionalOnProperty(name = "wao.auth.protection-enabled", havingValue = "true", matchIfMissing = true)
public class AuthInterceptor implements HandlerInterceptor {
    private final AuthProperties properties;
    private final AuthSessionManager sessions;

    public AuthInterceptor(AuthProperties properties, AuthSessionManager sessions) {
        this.properties = properties;
        this.sessions = sessions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.protectionEnabled() || isPublic(request)) return true;
        if (sessions.currentAccountId(request) == null) throw new UnauthorizedException("登录已过期，请重新登录");
        return true;
    }

    private boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.equals("/api/hello") || path.startsWith("/api/auth/") || path.startsWith("/api/share/")) return true;
        return "GET".equals(request.getMethod()) && path.matches("/api/images/[^/]+/content");
    }
}
