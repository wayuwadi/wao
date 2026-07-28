package cn.wayuwa.wao.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Builds and verifies the common Feishu OAuth authorization-code flow. */
@Service
public class FeishuLoginService {
    private static final Logger log = LoggerFactory.getLogger(FeishuLoginService.class);
    private static final Set<String> SCENES = Set.of("qr", "authorization", "workbench", "browser", "link");
    private final FeishuLoginProperties properties;
    private final RestClient client;
    public FeishuLoginService(FeishuLoginProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.client = builder.baseUrl(properties.openApiBaseUrl()).build();
    }

    /** Creates the OAuth authorization URL used by all four Web login scenarios. */
    public String authorizationUrl(String scene) {
        requireEnabled();
        if (!SCENES.contains(scene)) throw new IllegalArgumentException("Unsupported Feishu login scene");
        if (Set.of("qr", "workbench", "browser").contains(scene)) {
            return "https://passport.feishu.cn/suite/passport/oauth/authorize?client_id="
                    + encode(properties.appId()) + "&redirect_uri=" + encode(properties.redirectUri())
                    + "&response_type=code&state=" + encode(signedState(scene));
        }
        return properties.openApiBaseUrl() + "/open-apis/authen/v1/authorize?app_id=" + encode(properties.appId())
                + "&redirect_uri=" + encode(properties.redirectUri()) + "&state=" + encode(signedState(scene));
    }
    /** Verifies the callback state and returns the SPA callback URL. */
    public URI callbackUri(String code, String state) {
        verifyState(state);
        if (!StringUtils.hasText(code)) throw new IllegalArgumentException("Missing Feishu authorization code");
        return URI.create(properties.frontendUrl() + "/auth/feishu/callback?code=" + encode(code) + "&state=" + encode(state));
    }
    /** Exchanges the one-time code server-side and returns only basic user profile data. */
    public FeishuProfile exchange(String code, String state) {
        StatePayload statePayload = verifyState(state);
        if (!StringUtils.hasText(code)) throw new IllegalArgumentException("Missing Feishu authorization code");
        try {
            TokenResponse tokenResponse = Set.of("qr", "workbench", "browser").contains(statePayload.scene())
                    ? exchangeLegacyCode(code) : exchangeOAuthCode(code);
            return profileFrom(tokenResponse, statePayload.scene());
        } catch (RestClientResponseException exception) {
            log.warn("Feishu API request failed with HTTP {}", exception.getStatusCode());
            throw new FeishuLoginException("飞书认证服务暂时不可用，请稍后重试", exception);
        } catch (RestClientException exception) {
            log.warn("Feishu API request failed: {}", exception.getClass().getSimpleName());
            throw new FeishuLoginException("无法连接飞书认证服务，请稍后重试", exception);
        }
    }

    /**
     * 飞书容器内免登：JSSDK {@code tt.requestAuthCode} 拿到的临时码换用户身份。
     *
     * <p>这条链路没有浏览器重定向，也就没有 OAuth state 可验；临时码本身一次性且只能由
     * 本应用凭据兑换，真实性由飞书保证。
     */
    public FeishuProfile exchangeInApp(String code) {
        requireEnabled();
        if (!StringUtils.hasText(code)) throw new IllegalArgumentException("Missing Feishu authorization code");
        try {
            return profileFrom(exchangeLegacyCode(code), "in_app");
        } catch (RestClientResponseException exception) {
            log.warn("Feishu API request failed with HTTP {}", exception.getStatusCode());
            throw new FeishuLoginException("飞书认证服务暂时不可用，请稍后重试", exception);
        } catch (RestClientException exception) {
            log.warn("Feishu API request failed: {}", exception.getClass().getSimpleName());
            throw new FeishuLoginException("无法连接飞书认证服务，请稍后重试", exception);
        }
    }

    /** 应用是否配置齐全（前端据此决定要不要尝试容器内免登）。 */
    public boolean configured() {
        return properties.enabled() && StringUtils.hasText(properties.appId()) && StringUtils.hasText(properties.appSecret());
    }

    /** App ID 是公开信息，前端 JSSDK {@code requestAuthCode} 需要它。 */
    public String appId() {
        return properties.appId();
    }

    /** 用换到的 access_token 拉用户基本资料，各条登录链路共用。 */
    private FeishuProfile profileFrom(TokenResponse tokenResponse, String scene) {
        String accessToken = tokenResponse == null ? null : tokenResponse.resolvedAccessToken();
        if (!StringUtils.hasText(accessToken)) {
            String reason = tokenResponse == null ? "empty response" : tokenResponse.failureReason();
            log.warn("Feishu token exchange failed: {}", reason);
            throw new FeishuLoginException("飞书授权码交换失败，请重新登录");
        }

        UserInfoResponse userResponse = client.get().uri("/open-apis/authen/v1/user_info")
                .header("Authorization", "Bearer " + accessToken).retrieve().body(UserInfoResponse.class);
        User user = userResponse == null ? null : userResponse.data();
        String externalUserId = user == null ? null : user.stableId();
        if (!StringUtils.hasText(externalUserId)) {
            String reason = userResponse == null ? "empty response" : userResponse.msg();
            log.warn("Feishu user lookup failed: code={}, reason={}",
                    userResponse == null ? null : userResponse.code(), reason);
            throw new FeishuLoginException("未能读取飞书用户身份，请确认应用已发布且用户在可用范围内");
        }
        return new FeishuProfile(externalUserId, user.name(), user.avatarUrl(), user.email(), scene);
    }

    private TokenResponse exchangeOAuthCode(String code) {
        return client.post().uri("/open-apis/authen/v2/oauth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("grant_type", "authorization_code", "client_id", properties.appId(),
                        "client_secret", properties.appSecret(), "code", code,
                        "redirect_uri", properties.redirectUri()))
                .retrieve().body(TokenResponse.class);
    }

    private TokenResponse exchangeLegacyCode(String code) {
        AppAccessTokenResponse appToken = client.post().uri("/open-apis/auth/v3/app_access_token/internal")
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("app_id", properties.appId(), "app_secret", properties.appSecret()))
                .retrieve().body(AppAccessTokenResponse.class);
        if (appToken == null || !StringUtils.hasText(appToken.appAccessToken())) {
            log.warn("Feishu app access token lookup failed: {}", appToken == null ? "empty response" : appToken.msg());
            throw new FeishuLoginException("飞书应用凭证交换失败，请联系管理员检查应用配置");
        }
        return client.post().uri("/open-apis/authen/v1/access_token")
                .header("Authorization", "Bearer " + appToken.appAccessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("grant_type", "authorization_code", "code", code,
                        "app_id", properties.appId(), "app_secret", properties.appSecret()))
                .retrieve().body(TokenResponse.class);
    }
    private String signedState(String scene) {
        String payload = Instant.now().plusSeconds(300).getEpochSecond() + ":" + scene + ":" + UUID.randomUUID();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + sign(payload);
    }
    private StatePayload verifyState(String state) {
        requireEnabled();
        try {
            String[] parts = state == null ? new String[0] : state.split("\\.", 2);
            if (parts.length != 2) throw new IllegalArgumentException("Invalid OAuth state");
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String[] fields = payload.split(":", 3);
            if (fields.length != 3 || !SCENES.contains(fields[1])
                    || Instant.now().getEpochSecond() > Long.parseLong(fields[0])
                    || !java.security.MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8), parts[1].getBytes(StandardCharsets.UTF_8)))
                throw new IllegalArgumentException("Expired or invalid OAuth state");
            return new StatePayload(fields[1]);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Expired or invalid OAuth state", exception);
        }
    }
    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.appSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Cannot sign OAuth state", e); }
    }
    private void requireEnabled() {
        if (!properties.enabled() || !StringUtils.hasText(properties.appId()) || !StringUtils.hasText(properties.appSecret()))
            throw new FeishuLoginException("飞书登录尚未配置，请联系管理员");
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private record TokenResponse(Integer code, String msg, String error,
                                 @JsonProperty("error_description") String errorDescription,
                                 @JsonProperty("access_token") String accessToken, TokenData data) {
        String resolvedAccessToken() {
            return StringUtils.hasText(accessToken) ? accessToken : data == null ? null : data.accessToken();
        }

        String failureReason() {
            if (StringUtils.hasText(errorDescription)) return errorDescription;
            if (StringUtils.hasText(error)) return error;
            if (StringUtils.hasText(msg)) return msg;
            return code == null ? "missing access_token" : "code=" + code;
        }
    }
    private record TokenData(@JsonProperty("access_token") String accessToken) { }
    private record AppAccessTokenResponse(Integer code, String msg,
                                          @JsonProperty("app_access_token") String appAccessToken) { }
    private record UserInfoResponse(Integer code, String msg, User data) { }
    private record User(@JsonProperty("user_id") String userId,
                        @JsonProperty("open_id") String openId,
                        @JsonProperty("union_id") String unionId,
                        String name,
                        @JsonProperty("avatar_url") String avatarUrl,
                        String email) {
        String stableId() {
            if (StringUtils.hasText(userId)) return userId;
            if (StringUtils.hasText(openId)) return openId;
            return unionId;
        }
    }
    private record StatePayload(String scene) { }
    public record FeishuProfile(String userId, String name, String avatarUrl, String email, String scene) { }
}
