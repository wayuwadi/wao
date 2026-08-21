package cn.wayuwa.wao.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import cn.wayuwa.wao.common.BadRequestException;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Skill 上传和公开下载的 Redis 限速器；Redis 故障时不阻断主业务。 */
@Component
public class SkillRateLimiter {
    private final StringRedisTemplate redis;
    public SkillRateLimiter(ObjectProvider<StringRedisTemplate> redis) { this.redis = redis.getIfAvailable(); }

    /** 每个成员每日最多创建 10 个新包。 */
    public void checkUpload(String memberId) {
        String key = "wao:skill:upload:" + memberId + ":" + LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if (increment(key, Duration.ofDays(2)) > 10) throw new BadRequestException("今日 Skill 上传已达 10 次，请明天再试");
    }

    /** 同一网络地址每小时对同一 Skill 最多下载 20 次。 */
    public void checkDownload(String clientKey, String skillId) {
        long hour = System.currentTimeMillis() / 3_600_000L;
        String key = "wao:skill:download:" + clientKey + ":" + skillId + ":" + hour;
        if (increment(key, Duration.ofHours(2)) > 20) throw new BadRequestException("下载过于频繁，请稍后再试");
    }

    private long increment(String key, Duration ttl) {
        if (redis == null) return 0;
        try {
            Long value = redis.opsForValue().increment(key);
            if (value != null && value == 1) redis.expire(key, ttl);
            return value == null ? 0 : value;
        } catch (DataAccessException ex) {
            return 0;
        }
    }
}
