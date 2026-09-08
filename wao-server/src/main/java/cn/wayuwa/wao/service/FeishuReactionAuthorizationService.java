package cn.wayuwa.wao.service;

import cn.wayuwa.wao.auth.FeishuLoginProperties;
import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.FeishuReactionGrantEntity;
import cn.wayuwa.wao.mapper.FeishuReactionGrantMapper;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpSession;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** 独立的个人表情 OAuth：凭证按应用和真实用户隔离，不改变 WAO 登录绑定。 */
@Service
public class FeishuReactionAuthorizationService {
    public static final String SCOPE = "im:message.reactions:write_only offline_access";
    private static final String STATE = "feishu.reaction.state";
    private static final String VERIFIER = "feishu.reaction.verifier";
    private static final String EXPIRY = "feishu.reaction.expiry";
    private static final String APP = "feishu.reaction.app";
    private final SettingService settings;
    private final FeishuLoginProperties login;
    private final FeishuReactionGrantMapper grants;
    private final VaultCryptoService crypto;
    private final RestClient client;
    private final String tokenUrl;

    public FeishuReactionAuthorizationService(SettingService settings, FeishuLoginProperties login,
            FeishuReactionGrantMapper grants, VaultCryptoService crypto, RestClient.Builder builder,
            @Value("${feishu.reactions.token-url:https://accounts.feishu.cn/oauth/v3/token}") String tokenUrl) {
        this.settings = settings; this.login = login; this.grants = grants; this.crypto = crypto;
        this.client = builder.build(); this.tokenUrl = tokenUrl;
    }

    /** 创建绑定浏览器会话的单次授权，使用 PKCE；只请求个人表情与刷新权限。 */
    public String authorizationUrl(HttpSession session) {
        String appId = appId();
        if (!StringUtils.hasText(login.redirectUri())) throw new IllegalStateException("请先配置飞书登录回调地址");
        String state = "reaction." + UUID.randomUUID();
        String verifier = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        session.setAttribute(STATE, state); session.setAttribute(VERIFIER, verifier);
        session.setAttribute(EXPIRY, System.currentTimeMillis() + 300_000L); session.setAttribute(APP, appId);
        return "https://accounts.feishu.cn/open-apis/authen/v1/authorize?client_id=" + encode(appId)
                + "&redirect_uri=" + encode(login.redirectUri()) + "&response_type=code&state=" + encode(state)
                + "&scope=" + encode(SCOPE) + "&code_challenge_method=S256&code_challenge=" + digest(verifier);
    }

    /** 只兑换与发起浏览器匹配的一次性回调，真实身份从飞书 user_info 获取。 */
    @Transactional
    public String complete(HttpSession session, String state, String code) {
        if (session == null || state == null || !state.equals(session.getAttribute(STATE))
                || !(session.getAttribute(EXPIRY) instanceof Long expires) || expires < System.currentTimeMillis()
                || !appId().equals(session.getAttribute(APP))) throw new IllegalStateException("授权已过期，请从当前浏览器重新授权");
        String verifier = (String) session.getAttribute(VERIFIER);
        session.removeAttribute(STATE); session.removeAttribute(VERIFIER); session.removeAttribute(EXPIRY); session.removeAttribute(APP);
        if (!StringUtils.hasText(code)) throw new IllegalStateException("你尚未同意授权，可以重新尝试");
        JsonNode token = exchange(Map.of("grant_type", "authorization_code", "client_id", appId(), "client_secret", secret(),
                "code", code, "redirect_uri", login.redirectUri(), "code_verifier", verifier));
        JsonNode user;
        try {
            user = client.get().uri(login.openApiBaseUrl() + "/open-apis/authen/v1/user_info")
                    .header("Authorization", "Bearer " + token.path("access_token").asText()).retrieve().body(JsonNode.class);
        } catch (RuntimeException ex) { throw new IllegalStateException("未能核对飞书身份，请重新授权"); }
        String openId = user == null ? "" : user.path("data").path("open_id").asText();
        if (user == null || user.path("code").asInt(-1) != 0 || !openId.matches("ou_[A-Za-z0-9_-]+"))
            throw new IllegalStateException("未能核对飞书身份，请重新授权");
        var row = new FeishuReactionGrantEntity(); row.setId(key(appId(), openId)); row.setAppId(appId()); row.setOpenId(openId);
        row.setDisplayName(user.path("data").path("name").asText(openId));
        apply(row, token); grants.saveGrant(row);
        return row.getDisplayName();
    }

    /** 独立事务持久化旋转后的刷新凭证，避免卡片显示失败使一次性 refresh_token 回滚失效。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String accessToken(String openId) {
        var row = grants.lockById(key(appId(), openId));
        if (row == null || !appId().equals(row.getAppId()) || !openId.equals(row.getOpenId())) return null;
        if (row.getExpiresAt().isAfter(LocalDateTime.now().plusSeconds(60))) return crypto.decrypt(row.getAccessCipher(), row.getAccessNonce());
        if (row.getRefreshCipher() == null || row.getRefreshExpiresAt() == null
                || !row.getRefreshExpiresAt().isAfter(LocalDateTime.now().plusSeconds(60))) return null;
        JsonNode token;
        try {
            token = exchange(Map.of("grant_type", "refresh_token", "client_id", appId(), "client_secret", secret(),
                    "refresh_token", crypto.decrypt(row.getRefreshCipher(), row.getRefreshNonce())));
        } catch (RuntimeException ex) { return null; }
        apply(row, token); grants.saveGrant(row);
        return crypto.decrypt(row.getAccessCipher(), row.getAccessNonce());
    }

    private JsonNode exchange(Map<String, Object> body) {
        try {
            JsonNode result = client.post().uri(tokenUrl).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
            if (result == null || result.path("code").asInt(0) != 0 || result.hasNonNull("error")
                    || !StringUtils.hasText(result.path("access_token").asText()) || result.path("expires_in").asLong(0) <= 0
                    || !java.util.Arrays.asList(result.path("scope").asText().split(" +")).contains("im:message.reactions:write_only"))
                throw new IllegalStateException();
            return result;
        } catch (RuntimeException ex) {
            // 不传播可能携带令牌响应正文的 HTTP 异常。
            throw new IllegalStateException("飞书个人表情授权未完成，请确认已开通表情权限并重新授权");
        }
    }

    private void apply(FeishuReactionGrantEntity row, JsonNode token) {
        var access = crypto.encrypt(token.path("access_token").asText());
        row.setAccessCipher(access.ciphertext()); row.setAccessNonce(access.nonce());
        row.setExpiresAt(LocalDateTime.now().plusSeconds(token.path("expires_in").asLong()));
        String refreshToken = token.path("refresh_token").asText();
        if (StringUtils.hasText(refreshToken) && token.path("refresh_token_expires_in").asLong(0) > 0) {
            var refresh = crypto.encrypt(refreshToken); row.setRefreshCipher(refresh.ciphertext()); row.setRefreshNonce(refresh.nonce());
            row.setRefreshExpiresAt(LocalDateTime.now().plusSeconds(token.path("refresh_token_expires_in").asLong()));
        } else { row.setRefreshCipher(null); row.setRefreshNonce(null); row.setRefreshExpiresAt(null); }
    }

    private String appId() {
        String id = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
        if (!StringUtils.hasText(id) || !StringUtils.hasText(secret())) throw new IllegalStateException("通知机器人尚未配置，请联系管理员");
        return id;
    }
    private String secret() { return settings.get(SettingService.CHAT_SYNC_APP_SECRET, ""); }
    private static String encode(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    static String key(String appId, String openId) { return digest(appId + ":" + openId); }
    private static String digest(String value) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("无法创建授权校验"); }
    }
}
