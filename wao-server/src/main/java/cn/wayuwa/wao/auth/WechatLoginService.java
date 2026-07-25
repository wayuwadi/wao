package cn.wayuwa.wao.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 微信开放平台网站应用扫码 OAuth 流程。 */
@Service
public class WechatLoginService {
    private static final Logger log = LoggerFactory.getLogger(WechatLoginService.class);
    private static final Set<String> SCENES = Set.of("login", "link");
    private final WechatLoginProperties properties;
    private final RestClient client;

    public WechatLoginService(WechatLoginProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.client = builder.baseUrl(properties.openApiBaseUrl()).build();
    }

    /** 创建微信网站应用扫码授权地址。 */
    public String authorizationUrl(String scene) {
        requireEnabled();
        if (!SCENES.contains(scene)) throw new IllegalArgumentException("Unsupported Wechat login scene");
        return properties.authorizeBaseUrl() + "/connect/qrconnect?appid=" + encode(properties.appId())
                + "&redirect_uri=" + encode(properties.redirectUri())
                + "&response_type=code&scope=snsapi_login&state=" + encode(signedState(scene))
                + "#wechat_redirect";
    }

    /** 校验 state 后把微信回调转到 SPA 回调页。 */
    public URI callbackUri(String code, String state) {
        verifyState(state);
        if (!StringUtils.hasText(code)) throw new IllegalArgumentException("Missing Wechat authorization code");
        return URI.create(properties.frontendUrl() + "/auth/wechat/callback?code=" + encode(code)
                + "&state=" + encode(state));
    }

    /** 交换授权码并读取微信用户资料。 */
    public WechatProfile exchange(String code, String state) {
        StatePayload payload = verifyState(state);
        if (!StringUtils.hasText(code)) throw new IllegalArgumentException("Missing Wechat authorization code");
        try {
            Token token = client.get().uri(uri -> uri.path("/sns/oauth2/access_token")
                    .queryParam("appid", properties.appId()).queryParam("secret", properties.appSecret())
                    .queryParam("code", code).queryParam("grant_type", "authorization_code").build())
                    .retrieve().body(Token.class);
            if (token == null || !StringUtils.hasText(token.accessToken()) || !StringUtils.hasText(token.openId())) {
                log.warn("Wechat token exchange failed: {}", token == null ? "empty response" : token.errmsg());
                throw new WechatLoginException("微信授权码交换失败，请重新登录");
            }
            User user = client.get().uri(uri -> uri.path("/sns/userinfo")
                    .queryParam("access_token", token.accessToken()).queryParam("openid", token.openId())
                    .queryParam("lang", "zh_CN").build()).retrieve().body(User.class);
            if (user == null || !StringUtils.hasText(user.stableId())) {
                throw new WechatLoginException("未能读取微信用户身份，请确认网站应用已审核通过");
            }
            return new WechatProfile(user.stableId(), user.nickname(), user.headimgurl(), payload.scene());
        } catch (WechatLoginException exception) {
            throw exception;
        } catch (RestClientException exception) {
            log.warn("Wechat API request failed: {}", exception.getClass().getSimpleName());
            throw new WechatLoginException("微信认证服务暂时不可用，请稍后重试", exception);
        }
    }

    private String signedState(String scene) {
        String value = Instant.now().plusSeconds(300).getEpochSecond() + ":" + scene + ":" + UUID.randomUUID();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8))
                + "." + sign(value);
    }

    private StatePayload verifyState(String state) {
        requireEnabled();
        try {
            String[] parts = state == null ? new String[0] : state.split("\\.", 2);
            if (parts.length != 2) throw new IllegalArgumentException("Invalid OAuth state");
            String value = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String[] fields = value.split(":", 3);
            if (fields.length != 3 || !SCENES.contains(fields[1])
                    || Instant.now().getEpochSecond() > Long.parseLong(fields[0])
                    || !java.security.MessageDigest.isEqual(sign(value).getBytes(StandardCharsets.UTF_8),
                    parts[1].getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("Expired or invalid OAuth state");
            }
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
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot sign OAuth state", exception);
        }
    }

    private void requireEnabled() {
        if (!properties.enabled() || !StringUtils.hasText(properties.appId())
                || !StringUtils.hasText(properties.appSecret())) {
            throw new WechatLoginException("微信登录尚未配置，需要微信开放平台网站应用的 App ID 与 App Secret");
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private record StatePayload(String scene) { }
    private record Token(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("openid") String openId,
                         @JsonProperty("unionid") String unionId,
                         Integer errcode, String errmsg) { }
    private record User(String openid, String unionid, String nickname, String headimgurl) {
        String stableId() { return StringUtils.hasText(unionid) ? unionid : openid; }
    }
    public record WechatProfile(String userId, String name, String avatarUrl, String scene) { }
}
