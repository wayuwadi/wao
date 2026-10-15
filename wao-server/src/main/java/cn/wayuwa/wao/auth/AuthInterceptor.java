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
    private final cn.wayuwa.wao.service.NoteAssetPrivacyService noteAssets;

    public AuthInterceptor(AuthProperties properties, AuthSessionManager sessions) {
        this(properties,sessions,null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AuthInterceptor(AuthProperties properties, AuthSessionManager sessions,cn.wayuwa.wao.service.NoteAssetPrivacyService noteAssets) {
        this.properties = properties;
        this.sessions = sessions;
        this.noteAssets = noteAssets;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path=request.getRequestURI();
        if(path.startsWith("/api/share/") || path.matches("/api/docs/[^/]+/public-shares(?:/[^/]+)?")) {
            response.setHeader("Cache-Control","no-store");
            response.setHeader("X-Robots-Tag","noindex, nofollow, noarchive");
        }
        var asset=java.util.regex.Pattern.compile("^/api/(images|files)/([^/]+)/.*$").matcher(path);
        // 按 MVC 已解码的变量检查，不能让百分号编码或矩阵参数绕过素材权限。
        String assetId=null;
        if(asset.matches()) {
            Object variables=request.getAttribute(org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            assetId=variables instanceof java.util.Map<?,?> values && values.get("id") instanceof String id ? id
                : org.springframework.web.util.UriUtils.decode(asset.group(2),java.nio.charset.StandardCharsets.UTF_8).split(";",2)[0];
        }
        if(assetId!=null && noteAssets!=null && noteAssets.isPrivate(asset.group(1),assetId)) {
            request.setAttribute("wao.private-note-asset",true);
            response.setHeader("Cache-Control","private, no-store");
            response.setHeader("X-Robots-Tag","noindex, nofollow, noarchive");
            if(sessions.currentAccountId(request)==null) throw new cn.wayuwa.wao.common.NotFoundException("资源不存在或无权访问");
        }
        if (!properties.protectionEnabled() || isPublic(request)) return true;
        if (sessions.currentAccountId(request) == null) throw new UnauthorizedException("登录已过期，请重新登录");
        return true;
    }

    private boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Only the exact one-time ticket exchange is public; issuance stays authenticated.
        if ("POST".equals(request.getMethod()) && path.equals("/api/edge/exchange")) return true;
        // HTML upload also accepts a narrowly scoped workspace bearer; the controller validates it.
        if ("POST".equals(request.getMethod()) && path.equals("/api/hosted-pages")
                && request.getHeader("Authorization") != null) return true;
        // CLI preflight validates the dedicated publishing scope and optional project visibility.
        if ("GET".equals(request.getMethod()) && path.equals("/api/hosted-pages/publish-access")
                && request.getHeader("Authorization") != null) return true;
        // Only Pet CLI operations may delegate authentication to the scoped bearer controller.
        if (request.getHeader("Authorization") != null
                && (("GET".equals(request.getMethod()) && (path.equals("/api/pets/cli-access") || path.equals("/api/pets/mine")))
                    || ("POST".equals(request.getMethod()) && path.equals("/api/pets"))
                    || ("PUT".equals(request.getMethod()) && path.matches("/api/pets/[^/]+/visibility")))) return true;
        if ("GET".equals(request.getMethod()) && path.equals("/api/edge/ready")) return true;
        // 飞书事件订阅与卡片交互回调没有 WAO 会话，真实性由 Verification Token / Encrypt Key 保证
        if (path.equals("/api/hello") || path.startsWith("/api/auth/") || ("GET".equals(request.getMethod()) && path.startsWith("/api/share/"))
                || path.equals("/api/codex-archive/status") || path.equals("/api/codex-archive/batches")
                || path.startsWith("/api/public/") || path.equals("/api/mcp")
                || path.equals("/api/mcp/workspace")
                || path.startsWith("/api/ai/runner/") || path.equals("/api/feishu/events")) return true;
        // 下载确认页要在未登录状态下拿到文件名、出题并兑换票据；下载接口自身再校验票据或会话。
        if ("POST".equals(request.getMethod())
                && (path.matches("/api/files/[^/]+/challenge") || path.matches("/api/files/[^/]+/ticket"))) return true;
        return "GET".equals(request.getMethod())
                && (path.matches("/api/images/[^/]+/(?:raw|preview)") || path.matches("/api/files/[^/]+/download")
                    || path.matches("/api/files/[^/]+/meta") || path.matches("/api/files/[^/]+/public-preview") || path.matches("/api/pages/[^/]+(?:/icon)?"));
    }
}
