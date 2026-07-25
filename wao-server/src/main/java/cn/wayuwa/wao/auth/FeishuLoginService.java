package cn.wayuwa.wao.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** Builds and verifies the common Feishu OAuth authorization-code flow. */
@Service
public class FeishuLoginService {
    private final FeishuLoginProperties properties;
    private final RestClient client;
    public FeishuLoginService(FeishuLoginProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.client = builder.baseUrl(properties.openApiBaseUrl()).build();
    }

    /** Creates the OAuth authorization URL used by all four Web login scenarios. */
    public String authorizationUrl(String scene) {
        requireEnabled();
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
        verifyState(state);
        if (!StringUtils.hasText(code)) throw new IllegalArgumentException("Missing Feishu authorization code");
        Token token = client.post().uri("/open-apis/authen/v2/oauth/token")
                .body(java.util.Map.of("grant_type", "authorization_code", "client_id", properties.appId(),
                        "client_secret", properties.appSecret(), "code", code, "redirect_uri", properties.redirectUri()))
                .retrieve().body(Token.class);
        if (token == null || !StringUtils.hasText(token.accessToken())) throw new IllegalStateException("Feishu token exchange failed");
        User user = client.get().uri("/open-apis/authen/v1/user_info").header("Authorization", "Bearer " + token.accessToken())
                .retrieve().body(User.class);
        if (user == null || !StringUtils.hasText(user.userId())) throw new IllegalStateException("Feishu user lookup failed");
        return new FeishuProfile(user.userId(), user.name(), user.avatarUrl());
    }
    private String signedState(String scene) {
        String payload = Instant.now().plusSeconds(300).getEpochSecond() + ":" + scene + ":" + UUID.randomUUID();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + sign(payload);
    }
    private void verifyState(String state) {
        requireEnabled();
        String[] parts = state == null ? new String[0] : state.split("\\.", 2);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid OAuth state");
        String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String[] fields = payload.split(":", 3);
        if (fields.length != 3 || Instant.now().getEpochSecond() > Long.parseLong(fields[0])
                || !java.security.MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8), parts[1].getBytes(StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("Expired or invalid OAuth state");
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
            throw new IllegalStateException("Feishu login is not configured");
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private record Token(@JsonProperty("access_token") String accessToken) { }
    private record User(@JsonProperty("user_id") String userId, String name, @JsonProperty("avatar_url") String avatarUrl) { }
    public record FeishuProfile(String userId, String name, String avatarUrl) { }
}
