package cn.wayuwa.wao.mapper;

import java.time.LocalDateTime;

import cn.wayuwa.wao.domain.FeishuQuickCollectEventEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 飞书群聊快速收集事件。 */
@Mapper
public interface FeishuQuickCollectEventMapper extends BaseMapper<FeishuQuickCollectEventEntity> {

    /** 按飞书原消息定位已经真实创建的任务，不从标题或正文猜测来源。 */
    @Select("SELECT * FROM feishu_quick_collect_event WHERE message_id = #{messageId} "
            + "AND target_id IS NOT NULL AND status IN ('CREATED', 'UPDATED') "
            + "ORDER BY created_at DESC LIMIT 1")
    FeishuQuickCollectEventEntity findCreatedByMessageId(@Param("messageId") String messageId);

    /** 原子认领一条内容已变化的既有消息，防止双节点重复更新同一任务。 */
    @Update("""
            UPDATE feishu_quick_collect_event
            SET status = 'PROCESSING', error_message = NULL, updated_at = #{updatedAt}
            WHERE event_key = #{eventKey}
              AND target_id IS NOT NULL
              AND status IN ('CREATED', 'UPDATED', 'FAILED', 'REJECTED', 'IGNORED')
              AND (source_fingerprint IS NULL OR source_fingerprint <> #{fingerprint})
            """)
    int claimChangedMessage(@Param("eventKey") String eventKey,
            @Param("fingerprint") String fingerprint, @Param("updatedAt") LocalDateTime updatedAt);

    /** 重新认领一次尚未创建任务的瞬时失败，图片接口恢复后可自动补齐。 */
    @Update("""
            UPDATE feishu_quick_collect_event
            SET status = 'PROCESSING', error_message = NULL, updated_at = #{updatedAt}
            WHERE event_key = #{eventKey} AND target_id IS NULL AND status = 'FAILED'
            """)
    int retryFailedCreate(@Param("eventKey") String eventKey, @Param("updatedAt") LocalDateTime updatedAt);
}
