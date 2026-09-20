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
        // Only the exact one-time ticket exchange is public; issuance stays authenticated.
        if ("POST".equals(request.getMethod()) && path.equals("/api/edge/exchange")) return true;
        if ("GET".equals(request.getMethod()) && path.equals("/api/edge/ready")) return true;
        // 飞书事件订阅与卡片交互回调没有 WAO 会话，真实性由 Verification Token / Encrypt Key 保证
        if (path.equals("/api/hello") || path.startsWith("/api/auth/") || path.startsWith("/api/share/")
                || path.equals("/api/codex-archive/status") || path.equals("/api/codex-archive/batches")
                || path.startsWith("/api/public/") || path.equals("/api/mcp")
                || path.startsWith("/api/ai/runner/") || path.equals("/api/feishu/events")
                || path.equals("/api/music/netease/callback")) return true;
        // 下载确认页要在未登录状态下拿到文件名、出题并兑换票据；下载接口自身再校验票据或会话。
        if ("POST".equals(request.getMethod())
                && (path.matches("/api/files/[^/]+/challenge") || path.matches("/api/files/[^/]+/ticket"))) return true;
        return "GET".equals(request.getMethod())
                && (path.matches("/api/images/[^/]+/raw") || path.matches("/api/files/[^/]+/download")
                    || path.matches("/api/files/[^/]+/meta") || path.matches("/api/files/[^/]+/public-preview") || path.matches("/api/pages/[^/]+(?:/icon)?"));
    }
}
