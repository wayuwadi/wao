package cn.wayuwa.wao.service;

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
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Anthropic（Claude Code）的 OAuth 2.0 PKCE 客户端。
 *
 * <p>用的是 Claude Code CLI 自己那套公开客户端参数：授权码在浏览器里换到后由用户粘回 WAO，
 * 服务端凭本地保存的 {@code code_verifier} 换取令牌。因此 WAO 不需要任何 Anthropic 侧的密钥，
 * 也不需要一个能被外网回调的地址——这正是能在内网 Linux 主节点上跑通登录的原因。
 *
 * <p>所有地址都可通过配置覆盖：它们是第三方端点，Anthropic 调整路径时不应该要求改代码重新发版。
 */
@Component
public class AnthropicOAuthClient {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RestClient restClient;
    private final String clientId;
    private final String authorizeUrl;
    private final String tokenUrl;
    private final String redirectUri;
    private final String scopes;
    private final String apiBase;

    public AnthropicOAuthClient(RestClient.Builder builder,
            @Value("${wao.ai.claude.client-id:9d1c250a-e61b-44d9-88ed-5944d1962f5e}") String clientId,
            @Value("${wao.ai.claude.authorize-url:https://claude.com/cai/oauth/authorize}") String authorizeUrl,
            @Value("${wao.ai.claude.token-url:https://platform.claude.com/v1/oauth/token}") String tokenUrl,
            @Value("${wao.ai.claude.redirect-uri:https://platform.claude.com/oauth/code/callback}") String redirectUri,
            @Value("${wao.ai.claude.scopes:org:create_api_key user:profile user:inference}") String scopes,
            @Value("${wao.ai.claude.api-base:https://api.anthropic.com}") String apiBase) {
        this.restClient = builder.build();
        this.clientId = clientId;
        this.authorizeUrl = authorizeUrl;
        this.tokenUrl = tokenUrl;
        this.redirectUri = redirectUri;
        this.scopes = scopes;
        this.apiBase = apiBase;
    }

    /** 生成一个 PKCE code_verifier（43-128 字符的高熵串）。 */
    public String newCodeVerifier() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** 生成一次登录用的随机 state。 */
    public String newState() {
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * 拼出让用户在浏览器里打开的授权地址。
     *
     * @param state    本次登录的 state，回调时用来找回 code_verifier
     * @param verifier 本次登录的 PKCE code_verifier，只留在服务端
     */
    public String authorizeUrl(String state, String verifier) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", "true");
        params.put("client_id", clientId);
        params.put("response_type", "code");
        params.put("redirect_uri", redirectUri);
        params.put("scope", scopes);
        params.put("code_challenge", challenge(verifier));
        params.put("code_challenge_method", "S256");
        params.put("state", state);
        StringBuilder query = new StringBuilder();
        params.forEach((key, value) -> {
            if (query.length() > 0) query.append('&');
            query.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return authorizeUrl + "?" + query;
    }

    /**
     * 用授权码换取令牌。
     *
     * <p>用户从回调页复制回来的通常是 {@code code#state} 形式，这里两种都接受。
     */
    public TokenSet exchangeCode(String rawCode, String verifier, String state) {
        String code = rawCode == null ? "" : rawCode.trim();
        if (code.contains("#")) code = code.substring(0, code.indexOf('#'));
        if (!StringUtils.hasText(code)) throw new BadRequestException("授权码不能为空");
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "authorization_code");
        body.put("code", code);
        body.put("redirect_uri", redirectUri);
        body.put("client_id", clientId);
        body.put("code_verifier", verifier);
        body.put("state", state);
        return postToken(body, "授权码无效或已过期，请重新发起登录");
    }

    /** 用 refresh token 续期；refresh token 本身也可能被换成新的。 */
    public TokenSet refresh(String refreshToken) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshToken);
        body.put("client_id", clientId);
        return postToken(body, "刷新令牌已失效，请重新登录这个账号");
    }

    /** 读取账号资料（邮箱、套餐、组织）。 */
    public JsonNode profile(String accessToken) {
        return getJson(apiBase + "/api/oauth/profile", accessToken);
    }

    /** 读取用量额度（五小时窗口、七天窗口、额外用量）。 */
    public JsonNode usage(String accessToken) {
        return getJson(apiBase + "/api/oauth/usage", accessToken);
    }

    private JsonNode getJson(String url, String accessToken) {
        try {
            return restClient.get().uri(url)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("anthropic-beta", "oauth-2025-04-20")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new BadRequestException("调用 Anthropic 接口失败：" + ex.getMessage());
        }
    }

    private TokenSet postToken(Map<String, String> body, String failureHint) {
        JsonNode json;
        try {
            json = restClient.post().uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new BadRequestException(failureHint + "（" + ex.getMessage() + "）");
        }
        if (json == null || !json.hasNonNull("access_token")) {
            throw new BadRequestException(failureHint);
        }
        return new TokenSet(
                json.path("access_token").asText(),
                json.path("refresh_token").asText(null),
                json.path("expires_in").asLong(0),
                json.path("scope").asText(scopes));
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

    /** 一次令牌交换的结果。{@code expiresInSeconds} 为 0 表示服务端没给有效期。 */
    public record TokenSet(String accessToken, String refreshToken, long expiresInSeconds, String scope) {
    }
}
