package cn.wayuwa.wao.service;

import java.net.InetSocketAddress;
import java.net.Proxy;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
    private final String usageUrl;
    private final String issuer;

    public OpenAiCodexOAuthClient(RestClient.Builder builder,
            @Value("${wao.ai.codex.client-id:app_EMoamEEZ73f0CkXaXp7hrann}") String clientId,
            @Value("${wao.ai.codex.authorize-url:https://auth.openai.com/oauth/authorize}") String authorizeUrl,
            @Value("${wao.ai.codex.token-url:https://auth.openai.com/oauth/token}") String tokenUrl,
            @Value("${wao.ai.codex.redirect-uri:http://localhost:1455/auth/callback}") String redirectUri,
            @Value("${wao.ai.codex.usage-url:https://chatgpt.com/backend-api/wham/usage}") String usageUrl,
            @Value("${wao.ai.codex.issuer:https://auth.openai.com}") String issuer,
            @Value("${wao.ai.codex.proxy-url:}") String proxyUrl) {
        if (StringUtils.hasText(proxyUrl)) {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setProxy(parseProxy(proxyUrl));
            builder.requestFactory(requestFactory);
        }
        this.restClient = builder.build();
        this.clientId = clientId;
        this.authorizeUrl = authorizeUrl;
        this.tokenUrl = tokenUrl;
        this.redirectUri = redirectUri;
        this.usageUrl = usageUrl;
        this.issuer = issuer.replaceAll("/+$", "");
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

    /**
     * 按 Codex 官方 {@code --device-auth} 协议申请一次性短码。
     *
     * <p>设备码流程适用于 WAO 这类远程/无头服务：用户只在 OpenAI 页面确认短码，服务端随后
     * 轮询授权结果，不需要浏览器回调 {@code localhost:1455}。
     */
    public DeviceCode requestDeviceCode() {
        JsonNode json;
        try {
            json = restClient.post().uri(issuer + "/api/accounts/deviceauth/usercode")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("client_id", clientId))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new BadRequestException("Codex 设备码登录暂不可用，请稍后重试（" + ex.getMessage() + "）");
        }
        String deviceAuthId = json == null ? null : json.path("device_auth_id").asText(null);
        String userCode = json == null ? null : json.path("user_code").asText(null);
        if (!StringUtils.hasText(userCode) && json != null) userCode = json.path("usercode").asText(null);
        long interval = parseInterval(json == null ? null : json.get("interval"));
        if (!StringUtils.hasText(deviceAuthId) || !StringUtils.hasText(userCode)) {
            throw new BadRequestException("Codex 设备码服务返回的数据不完整，请稍后重试");
        }
        return new DeviceCode(issuer + "/codex/device", userCode, deviceAuthId, Math.max(1, interval));
    }

    /** 查询一次设备码是否已在 OpenAI 页面完成授权；403/404 表示仍在等待用户确认。 */
    public DevicePollResult pollDeviceCode(String deviceAuthId, String userCode) {
        try {
            ResponseEntity<JsonNode> response = restClient.post()
                    .uri(issuer + "/api/accounts/deviceauth/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("device_auth_id", deviceAuthId, "user_code", userCode))
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.FORBIDDEN.value()
                                    || status.value() == HttpStatus.NOT_FOUND.value(),
                            (request, pendingResponse) -> { })
                    .toEntity(JsonNode.class);
            if (response.getStatusCode().value() == HttpStatus.FORBIDDEN.value()
                    || response.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return DevicePollResult.waiting();
            }
            JsonNode json = response.getBody();
            String code = json == null ? null : json.path("authorization_code").asText(null);
            String verifier = json == null ? null : json.path("code_verifier").asText(null);
            if (!StringUtils.hasText(code) || !StringUtils.hasText(verifier)) {
                throw new BadRequestException("Codex 设备授权结果不完整，请重新登录");
            }
            return DevicePollResult.authorized(code, verifier);
        } catch (BadRequestException ex) {
            throw ex;
        } catch (RestClientException ex) {
            throw new BadRequestException("查询 Codex 登录状态失败，请稍后重试（" + ex.getMessage() + "）");
        }
    }

    /** 用设备码流程返回的授权码与 verifier 换取 Codex CLI 令牌。 */
    public TokenSet exchangeDeviceCode(DevicePollResult result) {
        if (!result.authorized()) throw new BadRequestException("Codex 设备授权尚未完成");
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", clientId);
        body.add("code", result.authorizationCode());
        body.add("redirect_uri", issuer + "/deviceauth/callback");
        body.add("code_verifier", result.codeVerifier());
        return postToken(body, "Codex 设备授权码无效或已过期，请重新登录");
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

    /**
     * 查询 ChatGPT/Codex 登录态的套餐、主次限额窗口与重置时间。
     *
     * <p>该接口与 Codex CLI 登录态同属 {@code chatgpt.com}，但并非稳定公开契约；调用失败时上层
     * 必须保留账号并展示可重试错误，不能把失败解释成 0% 用量。
     */
    public JsonNode usage(String accessToken, String accountId) {
        try {
            RestClient.RequestHeadersSpec<?> request = restClient.get().uri(usageUrl)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("User-Agent", "WAO")
                    .accept(MediaType.APPLICATION_JSON);
            if (StringUtils.hasText(accountId)) {
                request = request.header("ChatGPT-Account-Id", accountId);
            }
            return request.retrieve().body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new BadRequestException("Codex 额度查询失败，请稍后重试或重新登录（" + ex.getMessage() + "）");
        }
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

    private static long parseInterval(JsonNode value) {
        if (value == null || value.isNull()) return 5;
        if (value.canConvertToLong()) return value.asLong(5);
        try {
            return Long.parseLong(value.asText().trim());
        } catch (NumberFormatException ex) {
            return 5;
        }
    }

    private static Proxy parseProxy(String rawProxyUrl) {
        URI proxyUri;
        try {
            proxyUri = URI.create(rawProxyUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("wao.ai.codex.proxy-url 不是合法 URL", ex);
        }
        String path = proxyUri.getPath();
        if (!"http".equalsIgnoreCase(proxyUri.getScheme())
                || !StringUtils.hasText(proxyUri.getHost())
                || proxyUri.getPort() < 1 || proxyUri.getPort() > 65535
                || proxyUri.getUserInfo() != null
                || (StringUtils.hasText(path) && !"/".equals(path))
                || proxyUri.getQuery() != null
                || proxyUri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "wao.ai.codex.proxy-url 必须是带显式端口、且不含凭据和路径的 http URL");
        }
        return new Proxy(Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(proxyUri.getHost(), proxyUri.getPort()));
    }

    private record Callback(String code, String state) { }

    public record DeviceCode(String verificationUrl, String userCode, String deviceAuthId,
            long intervalSeconds) { }

    public record DevicePollResult(boolean authorized, String authorizationCode, String codeVerifier) {
        public static DevicePollResult waiting() { return new DevicePollResult(false, null, null); }
        public static DevicePollResult authorized(String code, String verifier) {
            return new DevicePollResult(true, code, verifier);
        }
    }

    public record TokenSet(String idToken, String accessToken, String refreshToken, long expiresInSeconds) { }
}
