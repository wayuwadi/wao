package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.AuthSessionManager;
import cn.wayuwa.wao.auth.ExternalIdentityProfile;
import cn.wayuwa.wao.auth.WechatLoginService;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.AuthResolutionDto;
import cn.wayuwa.wao.service.AccountAuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 微信网站应用扫码登录与身份绑定入口。 */
@RestController
@RequestMapping("/api/auth/wechat")
public class WechatLoginController {
    private final WechatLoginService wechat;
    private final AccountAuthService accountAuth;
    private final AuthSessionManager sessions;

    public WechatLoginController(WechatLoginService wechat, AccountAuthService accountAuth,
                                 AuthSessionManager sessions) {
        this.wechat = wechat;
        this.accountAuth = accountAuth;
        this.sessions = sessions;
    }

    @GetMapping("/authorize")
    public ApiResponse<Map<String, String>> authorize(@RequestParam(defaultValue = "login") String scene) {
        return ApiResponse.ok(Map.of("authorizationUrl", wechat.authorizationUrl(scene)));
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam String code, @RequestParam String state) {
        return ResponseEntity.status(302).location(wechat.callbackUri(code, state)).build();
    }

    @PostMapping("/exchange")
    public ApiResponse<AuthResolutionDto> exchange(@RequestBody ExchangeRequest body, HttpServletRequest request) {
        WechatLoginService.WechatProfile profile = wechat.exchange(body.code(), body.state());
        AccountAuthService.ExternalResolution resolution = accountAuth.resolveExternal(
                new ExternalIdentityProfile("wechat", profile.userId(), null, profile.name(), profile.avatarUrl()),
                sessions.currentAccountId(request), "link".equals(profile.scene()));
        if (resolution.session() != null) sessions.login(request, resolution.session().accountId());
        else sessions.pending(request, resolution.pending());
        return ApiResponse.ok(resolution.toDto());
    }

    public record ExchangeRequest(String code, String state) { }
}
