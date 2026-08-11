package cn.wayuwa.wao.agent.controller;

import cn.wayuwa.wao.agent.auth.AgentSessionManager;
import cn.wayuwa.wao.agent.common.ApiResponse;
import cn.wayuwa.wao.agent.common.UnauthorizedException;
import cn.wayuwa.wao.agent.dto.PlatformAccountDto;
import cn.wayuwa.wao.agent.dto.PlatformSummaryDto;
import cn.wayuwa.wao.agent.service.PlatformAccountService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 平台接管相关接口。 */
@RestController
@RequestMapping("/api/agent/platforms")
public class PlatformController {
    private final AgentSessionManager sessions;
    private final PlatformAccountService platformAccounts;

    public PlatformController(AgentSessionManager sessions, PlatformAccountService platformAccounts) {
        this.sessions = sessions;
        this.platformAccounts = platformAccounts;
    }

    /** 平台能力矩阵与已绑定账号。 */
    @GetMapping
    public ApiResponse<List<PlatformSummaryDto>> platforms(HttpServletRequest request) {
        return ApiResponse.ok(platformAccounts.summaries(accountId(request)));
    }

    /** 返回平台授权地址，由前端在新窗口打开（Agent 页面运行在 iframe 中）。 */
    @GetMapping("/{platform}/authorize-url")
    public ApiResponse<Map<String, String>> authorizeUrl(@PathVariable String platform, HttpServletRequest request) {
        String url = platformAccounts.authorizeUrl(accountId(request), platform, request.getSession(true));
        return ApiResponse.ok(Map.of("authorizeUrl", url));
    }

    /**
     * 平台 OAuth 回调。用户浏览器带着 WAO_SESSION 直接跳到这里，处理完返回一个自动关闭的页面。
     */
    @GetMapping(value = "/{platform}/callback", produces = MediaType.TEXT_HTML_VALUE)
    public String callback(@PathVariable String platform,
                           @RequestParam(required = false) String code,
                           @RequestParam(required = false) String state,
                           @RequestParam(name = "error_description", required = false) String errorDescription,
                           HttpServletRequest request) {
        if (errorDescription != null && !errorDescription.isBlank()) {
            return resultPage(false, "平台返回授权失败：" + escape(errorDescription));
        }
        try {
            PlatformAccountDto bound = platformAccounts.handleCallback(accountId(request), platform, code, state,
                    request.getSession(true));
            return resultPage(true, "已接管「" + escape(bound.displayName()) + "」，可以关闭本页面回到 Agent。");
        } catch (RuntimeException ex) {
            return resultPage(false, escape(ex.getMessage()));
        }
    }

    /** 解绑平台账号。 */
    @DeleteMapping("/accounts/{platformAccountId}")
    public ApiResponse<Void> unbind(@PathVariable String platformAccountId, HttpServletRequest request) {
        platformAccounts.unbind(accountId(request), platformAccountId);
        return ApiResponse.ok(null);
    }

    private String resultPage(boolean success, String message) {
        return """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>平台授权</title>
                <style>body{font-family:system-ui,-apple-system,"PingFang SC",sans-serif;background:#f6f7fb;
                display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
                .card{background:#fff;border-radius:16px;padding:32px 36px;max-width:420px;text-align:center;
                box-shadow:0 12px 40px rgba(16,24,40,.10)}
                h1{font-size:18px;margin:0 0 10px;color:%s}p{color:#475467;font-size:14px;line-height:1.7;margin:0}
                </style></head><body><div class="card"><h1>%s</h1><p>%s</p></div>
                <script>setTimeout(function(){window.close()},2500)</script></body></html>
                """.formatted(success ? "#12b76a" : "#d92d20", success ? "授权成功" : "授权失败", message);
    }

    /** 回调内容会直接进 HTML，必须转义。 */
    private String escape(String raw) {
        if (raw == null) return "";
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String accountId(HttpServletRequest request) {
        String accountId = sessions.currentAccountId(request);
        if (accountId == null) throw new UnauthorizedException("WAO 登录已过期，请重新登录后进入 Agent");
        return accountId;
    }
}
