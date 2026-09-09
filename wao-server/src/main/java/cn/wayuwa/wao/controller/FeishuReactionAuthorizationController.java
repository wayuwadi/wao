package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.service.FeishuReactionAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

/** 面向个人的授权页，不向群聊发送任何消息。 */
@RestController
@RequestMapping("/api/auth/feishu/reactions")
public class FeishuReactionAuthorizationController {
    private static final String RESULT = "feishu.reaction.result";
    private static final String RESULT_EXPIRY = "feishu.reaction.result.expiry";
    private final FeishuReactionAuthorizationService service;
    public FeishuReactionAuthorizationController(FeishuReactionAuthorizationService service) { this.service = service; }
    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page() {
        return html("授权个人表情", "授权后，点击提醒里的「做到了 / 没做到」，表情作者会显示为你本人。仅用于你主动点击的提醒，不会代发聊天消息。", true);
    }
    @GetMapping("/start")
    public ResponseEntity<?> start(HttpServletRequest request) {
        try { return ResponseEntity.status(302).location(URI.create(service.authorizationUrl(request.getSession()))).build(); }
        catch (IllegalStateException ex) { return html("暂时无法授权", ex.getMessage(), true); }
    }
    /** 从既有 OAuth 回调地址接回，不新增飞书重定向地址。 */
    public ResponseEntity<String> callback(HttpServletRequest request, String state, String code) {
        ResponseEntity<String> result;
        try { result = html("个人表情已授权", "授权成员：" + service.complete(request.getSession(false), state, code)
                + "。请回到原提醒再点击一次，你的选择会显示为你本人的表情。", false); }
        catch (RuntimeException ex) { result = html("授权未完成", ex instanceof IllegalStateException ? ex.getMessage() : "请重新授权后再试", true); }
        var session = request.getSession();
        session.setAttribute(RESULT, result.getBody());
        session.setAttribute(RESULT_EXPIRY, System.currentTimeMillis() + 600_000L);
        return ResponseEntity.status(303).location(URI.create("/api/auth/feishu/reactions/result"))
                .header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer").build();
    }
    /** 在不含授权码的地址展示短期结果，刷新不重复兑换一次性凭证。 */
    @GetMapping(value = "/result", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> result(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null && session.getAttribute(RESULT_EXPIRY) instanceof Long expires && expires > System.currentTimeMillis()
                && session.getAttribute(RESULT) instanceof String body) return response(body);
        return page();
    }
    private ResponseEntity<String> html(String title, String description, boolean authorize) {
        String body = """
                <!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s · WAO</title><style>body{margin:0;background:#f4f5fa;color:#262a38;font:16px/1.8 system-ui,sans-serif;display:grid;min-height:100dvh;place-items:center}main{box-sizing:border-box;width:min(480px,calc(100%% - 32px));padding:32px;background:white;border:1px solid #e6e8f2;border-radius:18px}h1{font-size:24px;margin:0 0 12px}p{color:#626779;overflow-wrap:anywhere}a{display:inline-block;padding:10px 18px;background:#4853df;color:white;border-radius:9px;text-decoration:none;margin-top:8px}.back{background:transparent;color:#4853df;padding-left:0;display:block}</style>
                <main><h1>%s</h1><p>%s</p>%s<a class="back" href="/settings?tab=account">返回账号与登录</a></main></html>
                """.formatted(HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(description),
                        authorize ? "<a href=\"/api/auth/feishu/reactions/start\">使用飞书授权个人表情</a>" : "");
        return response(body);
    }
    private ResponseEntity<String> response(String body) {
        return ResponseEntity.ok().contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8)).header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer").header("X-Frame-Options", "DENY").body(body);
    }
}
