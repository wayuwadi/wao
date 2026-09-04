package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.TianrenDailyReadingEntity;
import cn.wayuwa.wao.domain.TianrenNotificationEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 持久化投递与跨节点原子状态转换。 */
@Mapper
public interface TianrenNotificationMapper {
    String COLUMNS = "reading_id,state,channel,target_id,target_name,payload,message_id,message,updated_at";
    @Select("SELECT " + COLUMNS + " FROM tianren_notification WHERE reading_id = #{id}")
    TianrenNotificationEntity find(String id);

    @Insert("""
            INSERT IGNORE INTO tianren_notification
            (reading_id, state, channel, target_id, target_name, payload, message, updated_at)
            VALUES (#{readingId}, #{state}, #{channel}, #{targetId}, #{targetName}, #{payload}, #{message}, #{updatedAt})
            """)
    int insert(TianrenNotificationEntity row);

    @Select("""
            SELECT r.* FROM tianren_daily_reading r
            WHERE r.reading_date = #{date} AND r.id > #{afterId} AND r.conversation_id IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM tianren_notification n WHERE n.reading_id = r.id)
            ORDER BY r.id LIMIT 50
            """)
    List<TianrenDailyReadingEntity> candidates(@Param("date") LocalDate date, @Param("afterId") String afterId);

    @Select("SELECT " + COLUMNS + " FROM tianren_notification WHERE state = 'PENDING' ORDER BY updated_at LIMIT 20")
    List<TianrenNotificationEntity> pending();

    @Update("""
            UPDATE tianren_notification SET state = 'SENDING', attempt_token = #{token}, message = '正在发送', updated_at = #{now}
            WHERE reading_id = #{id} AND state = 'PENDING'
            """)
    int claim(@Param("id") String id, @Param("token") String token, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE tianren_notification SET state = #{next}, message = #{message}, message_id = #{messageId},
                attempt_token = NULL, updated_at = #{now}
            WHERE reading_id = #{id} AND state = 'SENDING' AND attempt_token = #{token}
            """)
    int finish(@Param("id") String id, @Param("token") String token, @Param("next") String next,
            @Param("message") String message, @Param("messageId") String messageId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE tianren_notification SET state = #{next}, message = #{message},
                message_id = #{messageId}, updated_at = #{now}
            WHERE reading_id = #{id} AND state = #{expected}
            """)
    int transition(@Param("id") String id, @Param("expected") String expected, @Param("next") String next,
            @Param("message") String message, @Param("messageId") String messageId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE tianren_notification SET state = 'UNKNOWN', message = '发送中断，请先在群里确认是否已收到再重试',
                updated_at = #{now} WHERE state = 'SENDING' AND updated_at < #{cutoff}
            """)
    int expire(@Param("now") LocalDateTime now, @Param("cutoff") LocalDateTime cutoff);
}
