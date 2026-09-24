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
            + "AND (capture_kind IS NULL OR capture_kind IN ('TASK', 'IDEA')) "
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

    /** 先把原消息标成撤回处理中；后到的接收事件不得再把任务创建回来。 */
    @Update("""
            UPDATE feishu_quick_collect_event
            SET status = 'RECALLING', error_message = NULL, updated_at = #{updatedAt}
            WHERE event_key = #{eventKey} AND status <> 'RECALLED'
            """)
    int markRecallPending(@Param("eventKey") String eventKey, @Param("updatedAt") LocalDateTime updatedAt);

    /** 任务已确认不存在后才完成撤回，后续轮询无需重复删除。 */
    @Update("""
            UPDATE feishu_quick_collect_event
            SET status = 'RECALLED', error_message = NULL, updated_at = #{updatedAt}
            WHERE event_key = #{eventKey} AND status = 'RECALLING'
            """)
    int completeRecall(@Param("eventKey") String eventKey, @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 完成一次创建或更新，但不覆盖并发到达的撤回墓碑。
     *
     * <p>撤回与首次创建可能落到不同节点；条件更新让撤回先胜时新建任务能在调用方立即清理。
     */
    @Update("""
            UPDATE feishu_quick_collect_event
            SET status = #{status}, capture_kind = #{kind},
                target_id = COALESCE(#{targetId}, target_id),
                source_fingerprint = COALESCE(#{fingerprint}, source_fingerprint),
                error_message = #{error}, updated_at = #{updatedAt}
            WHERE event_key = #{eventKey} AND status NOT IN ('RECALLING', 'RECALLED')
            """)
    int finishUnlessRecalled(@Param("eventKey") String eventKey, @Param("status") String status,
            @Param("kind") String kind, @Param("targetId") String targetId,
            @Param("fingerprint") String fingerprint, @Param("error") String error,
            @Param("updatedAt") LocalDateTime updatedAt);

    /** 撤回先到、创建后到时补齐审计目标，墓碑状态保持不变。 */
    @Update("""
            UPDATE feishu_quick_collect_event
            SET target_id = COALESCE(target_id, #{targetId}),
                source_fingerprint = COALESCE(#{fingerprint}, source_fingerprint),
                updated_at = #{updatedAt}
            WHERE event_key = #{eventKey} AND status IN ('RECALLING', 'RECALLED')
            """)
    int attachRecalledTarget(@Param("eventKey") String eventKey, @Param("targetId") String targetId,
            @Param("fingerprint") String fingerprint, @Param("updatedAt") LocalDateTime updatedAt);
}
