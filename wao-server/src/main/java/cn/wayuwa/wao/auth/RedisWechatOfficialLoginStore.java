package cn.wayuwa.wao.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Redis 实现保证浏览器轮询和微信回调命中不同应用节点时仍共享同一状态。 */
@Component
class RedisWechatOfficialLoginStore implements WechatOfficialLoginStore {
    private static final String PREFIX = "wao:wechat-official:";
    private static final String TOKEN_KEY = PREFIX + "access-token";
    private final StringRedisTemplate redis;

    RedisWechatOfficialLoginStore(ObjectProvider<StringRedisTemplate> redis) {
        this.redis = redis.getIfAvailable();
    }

    @Override
    public void save(LoginSession session) {
        Duration ttl = ttl(session.expiresAt());
        requireRedis().opsForHash().putAll(sessionKey(session.id()), Map.of(
                "code", session.verificationCode(),
                "status", session.status().name(),
                "expiresAt", session.expiresAt().toString(),
                "qrCodeUrl", session.qrCodeUrl()));
        requireRedis().expire(sessionKey(session.id()), ttl);
        requireRedis().opsForValue().set(sceneKey(session.id()), session.id(), ttl);
    }

    @Override
    public Optional<LoginSession> find(String sessionId) {
        Map<Object, Object> values = requireRedis().opsForHash().entries(sessionKey(sessionId));
        if (values.isEmpty()) return Optional.empty();
        Instant expiresAt = Instant.parse(String.valueOf(values.get("expiresAt")));
        if (!expiresAt.isAfter(Instant.now())) return Optional.empty();
        String openId = value(values, "openId");
        return Optional.of(new LoginSession(sessionId, value(values, "code"),
                Status.valueOf(value(values, "status")), openId, expiresAt, value(values, "qrCodeUrl")));
    }

    @Override
    public boolean markScanned(String scene, String openId) {
        String sessionId = requireRedis().opsForValue().get(sceneKey(scene));
        Optional<LoginSession> session = StringUtils.hasText(sessionId) ? find(sessionId) : Optional.empty();
        if (session.isEmpty()) return false;
        Duration ttl = ttl(session.get().expiresAt());
        requireRedis().opsForHash().put(sessionKey(sessionId), "status", Status.WAITING_CODE.name());
        requireRedis().opsForHash().put(sessionKey(sessionId), "openId", openId);
        requireRedis().opsForValue().set(scanKey(openId), sessionId, ttl);
        return true;
    }

    @Override
    public CodeResult complete(String openId, String verificationCode) {
        String sessionId = requireRedis().opsForValue().get(scanKey(openId));
        Optional<LoginSession> session = StringUtils.hasText(sessionId) ? find(sessionId) : Optional.empty();
        if (session.isEmpty()) return CodeResult.NO_ACTIVE_SCAN;
        if (!session.get().verificationCode().equals(verificationCode)) return CodeResult.INVALID;
        requireRedis().opsForHash().put(sessionKey(sessionId), "status", Status.COMPLETED.name());
        return CodeResult.MATCHED;
    }

    @Override
    public Optional<String> accessToken() {
        return Optional.ofNullable(requireRedis().opsForValue().get(TOKEN_KEY)).filter(StringUtils::hasText);
    }

    @Override
    public void saveAccessToken(String accessToken, long expiresInSeconds) {
        requireRedis().opsForValue().set(TOKEN_KEY, accessToken,
                Duration.ofSeconds(Math.max(60, expiresInSeconds - 120)));
    }

    @Override
    public void clearAccessToken() {
        requireRedis().delete(TOKEN_KEY);
    }

    private StringRedisTemplate requireRedis() {
        if (redis == null) throw new WechatLoginException("公众号登录状态服务未就绪，请稍后重试");
        return redis;
    }

    private static String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Duration ttl(Instant expiresAt) {
        return Duration.ofSeconds(Math.max(1, Duration.between(Instant.now(), expiresAt).toSeconds()));
    }

    private static String sessionKey(String id) { return PREFIX + "session:" + id; }
    private static String sceneKey(String scene) { return PREFIX + "scene:" + scene; }
    private static String scanKey(String openId) { return PREFIX + "scan:" + openId; }
}
