package cn.wayuwa.wao.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 共享聊天轮询水位与双节点租约。 */
@Mapper
public interface SharedChatSyncStateMapper {
    @Update("""
            UPDATE shared_chat_sync_state
               SET cursor_epoch_seconds = CASE WHEN chat_id IS NULL OR chat_id <> #{chatId}
                                                THEN #{initialCursor} ELSE cursor_epoch_seconds END,
                   chat_id = #{chatId}, lease_owner = NULL, lease_until = NULL, last_success_at = NULL
             WHERE id = #{id} AND (chat_id IS NULL OR chat_id <> #{chatId})
            """)
    int prepare(@Param("id") String id, @Param("chatId") String chatId,
            @Param("initialCursor") long initialCursor);

    @Update("""
            UPDATE shared_chat_sync_state SET lease_owner = #{owner}, lease_until = #{until}
             WHERE id = #{id} AND (lease_until IS NULL OR lease_until < #{now} OR lease_owner = #{owner})
            """)
    int claim(@Param("id") String id, @Param("owner") String owner,
            @Param("now") LocalDateTime now, @Param("until") LocalDateTime until);

    /** 原子检查执行租约及成功后的冷却时间，避免另一节点紧接着重复轮询。 */
    @Update("""
            UPDATE shared_chat_sync_state SET lease_owner = #{owner},
                lease_until = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 60 MINUTE)
             WHERE id = #{id} AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP(6))
               AND (last_success_at IS NULL OR
                    last_success_at <= TIMESTAMPADD(MICROSECOND, -#{intervalMicros}, CURRENT_TIMESTAMP(6)))
            """)
    int claimPolling(@Param("id") String id, @Param("owner") String owner,
            @Param("intervalMicros") long intervalMicros);

    @Select("SELECT cursor_epoch_seconds FROM shared_chat_sync_state WHERE id = #{id}")
    Long cursor(@Param("id") String id);

    @Update("""
            UPDATE shared_chat_sync_state
               SET cursor_epoch_seconds = #{cursor}, last_success_at = CURRENT_TIMESTAMP(6),
                   lease_owner = NULL, lease_until = NULL
             WHERE id = #{id} AND lease_owner = #{owner}
            """)
    int complete(@Param("id") String id, @Param("owner") String owner, @Param("cursor") long cursor);

    @Update("""
            UPDATE shared_chat_sync_state SET lease_owner = NULL, lease_until = NULL
             WHERE id = #{id} AND lease_owner = #{owner}
            """)
    int release(@Param("id") String id, @Param("owner") String owner);

    /** 失败后的双节点共享冷却；保留水位，不允许另一节点立即重试。 */
    @Update("""
            UPDATE shared_chat_sync_state SET lease_owner = NULL,
                lease_until = TIMESTAMPADD(SECOND, #{seconds}, CURRENT_TIMESTAMP(6))
             WHERE id = #{id} AND lease_owner = #{owner}
            """)
    int defer(@Param("id") String id, @Param("owner") String owner, @Param("seconds") long seconds);
}
