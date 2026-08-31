package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.AuthSessionManager;
import cn.wayuwa.wao.auth.ExternalIdentityProfile;
import cn.wayuwa.wao.auth.WechatOfficialAccountLoginService;
import cn.wayuwa.wao.auth.WechatOfficialLoginStore;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.AuthResolutionDto;
import cn.wayuwa.wao.service.AccountAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 微信公众号扫码、验证码消息回调和浏览器登录轮询入口。 */
@RestController
@RequestMapping("/api/auth/wechat/official")
public class WechatOfficialAccountLoginController {
    private static final String BROWSER_SESSION = "wao.wechatOfficialSession";
    private final WechatOfficialAccountLoginService wechat;
    private final AccountAuthService accountAuth;
    private final AuthSessionManager sessions;

    public WechatOfficialAccountLoginController(WechatOfficialAccountLoginService wechat,
                                                AccountAuthService accountAuth, AuthSessionManager sessions) {
        this.wechat = wechat;
        this.accountAuth = accountAuth;
        this.sessions = sessions;
    }

    @PostMapping("/sessions")
    public ApiResponse<StartResponse> createSession(HttpServletRequest request) {
        WechatOfficialLoginStore.LoginSession created = wechat.createSession();
        request.getSession(true).setAttribute(BROWSER_SESSION, created.id());
        return ApiResponse.ok(new StartResponse(created.id(), created.qrCodeUrl(), created.verificationCode(),
                created.expiresAt()));
    }

    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<PollResponse> poll(@PathVariable String sessionId, HttpServletRequest request) {
        HttpSession browser = request.getSession(false);
        if (browser == null || !sessionId.equals(browser.getAttribute(BROWSER_SESSION))) {
            throw new UnauthorizedException("公众号登录会话不属于当前浏览器，请重新扫码");
        }
        WechatOfficialLoginStore.LoginSession state = wechat.session(sessionId).orElse(null);
        if (state == null) return ApiResponse.ok(new PollResponse("EXPIRED", null));
        if (state.status() != WechatOfficialLoginStore.Status.COMPLETED) {
            return ApiResponse.ok(new PollResponse(state.status().name(), null));
        }
        WechatOfficialAccountLoginService.OfficialProfile profile = wechat.profile(state.openId());
        AccountAuthService.ExternalResolution resolution = accountAuth.resolveExternal(
                new ExternalIdentityProfile("wechat", profile.userId(), null, profile.name(), profile.avatarUrl(),
                        profile.unionId()), null, false);
        if (resolution.session() != null) sessions.login(request, resolution.session().accountId());
        else sessions.pending(request, resolution.pending());
        return ApiResponse.ok(new PollResponse("COMPLETED", resolution.toDto()));
    }

    @GetMapping(value = "/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(@RequestParam String signature, @RequestParam String timestamp,
                                         @RequestParam String nonce, @RequestParam String echostr) {
        return wechat.validSignature(signature, timestamp, nonce)
                ? ResponseEntity.ok(echostr) : ResponseEntity.status(401).body("");
    }

    @PostMapping(value = "/callback", consumes = {MediaType.TEXT_XML_VALUE, MediaType.APPLICATION_XML_VALUE},
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> callback(@RequestParam String signature, @RequestParam String timestamp,
                                           @RequestParam String nonce, @RequestBody String body) {
        if (!wechat.validSignature(signature, timestamp, nonce)) return ResponseEntity.status(401).body("");
        return ResponseEntity.ok(wechat.handleMessage(body));
    }

    public record StartResponse(String sessionId, String qrCodeUrl, String verificationCode, Instant expiresAt) { }
    public record PollResponse(String status, AuthResolutionDto resolution) { }
}
