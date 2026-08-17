package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.FeishuLoginService;
import cn.wayuwa.wao.auth.AuthSessionManager;
import cn.wayuwa.wao.auth.ExternalIdentityProfile;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.AuthResolutionDto;
import cn.wayuwa.wao.service.AccountAuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public Feishu entry points. The app secret never reaches the browser. */
@RestController
@RequestMapping("/api/auth/feishu")
public class FeishuLoginController {
    private final FeishuLoginService service;
    private final AccountAuthService accountAuth;
    private final AuthSessionManager sessions;
    public FeishuLoginController(FeishuLoginService service, AccountAuthService accountAuth,
                                 AuthSessionManager sessions) {
        this.service = service;
        this.accountAuth = accountAuth;
        this.sessions = sessions;
    }
    @GetMapping("/authorize")
    public ApiResponse<Map<String, String>> authorize(
            @RequestParam(defaultValue = "authorization") String scene,
            @RequestParam(required = false) String callbackUrl) {
        return ApiResponse.ok(Map.of("authorizationUrl", service.authorizationUrl(scene, callbackUrl)));
    }
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam String code, @RequestParam String state) {
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, service.callbackUri(code, state).toString()).build();
    }
    @GetMapping("/workbench")
    public ResponseEntity<Void> workbench() { return redirect("workbench"); }
    @GetMapping("/browser")
    public ResponseEntity<Void> browser() { return redirect("browser"); }
    @PostMapping("/exchange")
    public ApiResponse<AuthResolutionDto> exchange(@RequestBody ExchangeRequest body, HttpServletRequest request) {
        FeishuLoginService.FeishuProfile profile = service.exchange(body.code(), body.state());
        return resolve(profile, request);
    }
    /** 容器内免登前置信息：前端先问一下能不能免登、JSSDK 要用的 App ID 是什么。 */
    @GetMapping("/in-app-config")
    public ApiResponse<Map<String, Object>> inAppConfig() {
        boolean enabled = service.configured();
        return ApiResponse.ok(Map.of("enabled", enabled, "appId", enabled ? service.appId() : ""));
    }
    /** 容器内免登：JSSDK requestAuthCode 拿到的临时码直接换 WAO 会话。 */
    @PostMapping("/in-app-exchange")
    public ApiResponse<AuthResolutionDto> inAppExchange(@RequestBody InAppExchangeRequest body, HttpServletRequest request) {
        return resolve(service.exchangeInApp(body.code()), request);
    }
    private ApiResponse<AuthResolutionDto> resolve(FeishuLoginService.FeishuProfile profile, HttpServletRequest request) {
        AccountAuthService.ExternalResolution resolution = accountAuth.resolveExternal(
                new ExternalIdentityProfile("feishu", profile.userId(), profile.email(), profile.name(), profile.avatarUrl()),
                sessions.currentAccountId(request), "link".equals(profile.scene()));
        if (resolution.session() != null) sessions.login(request, resolution.session().accountId());
        else sessions.pending(request, resolution.pending());
        return ApiResponse.ok(resolution.toDto());
    }
    private ResponseEntity<Void> redirect(String scene) {
        return ResponseEntity.status(302).location(URI.create(service.authorizationUrl(scene))).build();
    }
    public record ExchangeRequest(String code, String state) { }
    public record InAppExchangeRequest(String code) { }
}
