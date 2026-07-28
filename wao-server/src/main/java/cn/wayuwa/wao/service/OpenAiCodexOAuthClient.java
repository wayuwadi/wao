package cn.wayuwa.wao.service;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import cn.wayuwa.wao.common.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** OpenAI Codex CLI 使用的 OAuth 2.0 PKCE 客户端。 */
@Component
public class OpenAiCodexOAuthClient {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RestClient restClient;
    private final String clientId;
    private final String authorizeUrl;
    private final String tokenUrl;
    private final String redirectUri;

    public OpenAiCodexOAuthClient(RestClient.Builder builder,
            @Value("${wao.ai.codex.client-id:app_EMoamEEZ73f0CkXaXp7hrann}") String clientId,
            @Value("${wao.ai.codex.authorize-url:https://auth.openai.com/oauth/authorize}") String authorizeUrl,
            @Value("${wao.ai.codex.token-url:https://auth.openai.com/oauth/token}") String tokenUrl,
            @Value("${wao.ai.codex.redirect-uri:http://localhost:1455/auth/callback}") String redirectUri) {
        this.restClient = builder.build();
        this.clientId = clientId;
        this.authorizeUrl = authorizeUrl;
        this.tokenUrl = tokenUrl;
        this.redirectUri = redirectUri;
    }

    /** 生成满足 PKCE 长度要求的随机 code_verifier。 */
    public String newCodeVerifier() {
        byte[] raw = new byte[64];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** 生成一次性 OAuth state，用于关联并校验回填会话。 */
    public String newState() {
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** 生成与 Codex CLI 一致的浏览器授权地址。 */
    public String authorizeUrl(String state, String verifier) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", clientId);
        params.put("response_type", "code");
        params.put("redirect_uri", redirectUri);
        params.put("scope", "openid profile email offline_access api.connectors.read api.connectors.invoke");
        params.put("state", state);
        params.put("code_challenge", challenge(verifier));
        params.put("code_challenge_method", "S256");
        params.put("prompt", "login");
        params.put("id_token_add_organizations", "true");
        params.put("codex_cli_simplified_flow", "true");
        params.put("originator", "codex_cli_rs");
        StringBuilder query = new StringBuilder();
        params.forEach((key, value) -> {
            if (query.length() > 0) query.append('&');
            query.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return authorizeUrl + "?" + query;
    }

    /** 接受纯授权码或浏览器地址栏里的完整 localhost 回调地址。 */
    public TokenSet exchangeCode(String rawCode, String verifier, String expectedState) {
        Callback callback = callback(rawCode);
        if (StringUtils.hasText(callback.state()) && !expectedState.equals(callback.state())) {
            throw new BadRequestException("登录 state 不匹配，请重新发起 Codex 登录");
        }
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", clientId);
        body.add("code", callback.code());
        body.add("redirect_uri", redirectUri);
        body.add("code_verifier", verifier);
        return postToken(body, "授权码无效或已过期，请重新发起 Codex 登录");
    }

    /** 使用 refresh token 换取一组新的 Codex CLI 令牌。 */
    public TokenSet refresh(String refreshToken) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("grant_type", "refresh_token");
        body.add("refresh_token", refreshToken);
        body.add("scope", "openid profile email");
        return postToken(body, "Codex 刷新令牌已失效，请重新登录这个账号");
    }

    private TokenSet postToken(MultiValueMap<String, String> body, String failureHint) {
        JsonNode json;
        try {
            json = restClient.post().uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new BadRequestException(failureHint + "（" + ex.getMessage() + "）");
        }
        if (json == null || !json.hasNonNull("access_token") || !json.hasNonNull("id_token")) {
            throw new BadRequestException(failureHint);
        }
        return new TokenSet(json.path("id_token").asText(), json.path("access_token").asText(),
                json.path("refresh_token").asText(null), json.path("expires_in").asLong(0));
    }

    private static Callback callback(String rawCode) {
        String text = rawCode == null ? "" : rawCode.trim();
        if (!StringUtils.hasText(text)) throw new BadRequestException("授权码不能为空");
        if (!text.contains("code=")) return new Callback(text, null);
        try {
            String query = URI.create(text).getRawQuery();
            if (!StringUtils.hasText(query)) throw new IllegalArgumentException("缺少 query");
            Map<String, String> values = new LinkedHashMap<>();
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
            }
            String code = values.get("code");
            if (!StringUtils.hasText(code)) throw new IllegalArgumentException("缺少 code");
            return new Callback(code, values.get("state"));
        } catch (RuntimeException ex) {
            throw new BadRequestException("回调地址格式不正确，请复制浏览器地址栏里的完整地址");
        }
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private record Callback(String code, String state) { }

    public record TokenSet(String idToken, String accessToken, String refreshToken, long expiresInSeconds) { }
}
