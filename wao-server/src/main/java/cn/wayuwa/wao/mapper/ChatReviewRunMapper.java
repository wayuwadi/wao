package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.ChatReviewRunEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 共享聊天复盘运行及跨节点租约。 */
@Mapper
public interface ChatReviewRunMapper extends BaseMapper<ChatReviewRunEntity> {

    /** 用主库时钟创建等待运行，多 API 节点的本地时钟不影响历史顺序。 */
    @Insert("""
            INSERT INTO chat_review_run
                (id, chat_id, chat_name, kb_id,
                 speaker_one_id, speaker_one_name, speaker_two_id, speaker_two_name,
                 start_date, end_date, focus_text, stage,
                 progress_current, progress_total, message_count, retry_of_id,
                 created_by, created_by_name, created_at, updated_at)
            VALUES
                (#{run.id}, #{run.chatId}, #{run.chatName}, #{run.kbId},
                 #{run.speakerOneId}, #{run.speakerOneName}, #{run.speakerTwoId}, #{run.speakerTwoName},
                 #{run.startDate}, #{run.endDate}, #{run.focusText}, #{run.stage},
                 #{run.progressCurrent}, #{run.progressTotal}, #{run.messageCount}, #{run.retryOfId},
                 #{run.createdBy}, #{run.createdByName}, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
            """)
    int insertWaiting(@Param("run") ChatReviewRunEntity run);

    /** 仅在租约空闲或过期时抢占运行，受影响一行才表示抢占成功。 */
    @Update("""
            UPDATE chat_review_run
               SET worker_token = #{workerToken},
                   lease_until = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 MINUTE),
                   updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = #{id}
               AND stage IN ('WAITING', 'SYNCING', 'VERIFYING', 'ANALYZING')
               AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP(6)
                    OR worker_token = #{workerToken})
            """)
    int claimLease(@Param("id") String id, @Param("workerToken") String workerToken);

    /**
     * 只有当前租约持有者可以把已入队的 AI 运行与复盘绑定。
     *
     * <p>该更新与 {@code ai_assistant_run} 插入处在同一事务中；租约已丢失时返回 0，
     * 让上层回滚新增的 AI 运行，避免重复入队。
     */
    @Update("""
            UPDATE chat_review_run
               SET ai_run_id = #{aiRunId}, stage = 'ANALYZING',
                   processing_date = NULL,
                   lease_until = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 MINUTE),
                   updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = #{id}
               AND stage = 'VERIFYING'
               AND ai_run_id IS NULL
               AND worker_token = #{workerToken}
               AND lease_until > CURRENT_TIMESTAMP(6)
            """)
    int linkAiRun(@Param("id") String id, @Param("workerToken") String workerToken,
            @Param("aiRunId") String aiRunId);

    /** 一次快速处理结束后主动释放租约，让下一轮可继续查询 AI 结果。 */
    @Update("""
            UPDATE chat_review_run
               SET worker_token = NULL, lease_until = NULL
             WHERE id = #{id} AND worker_token = #{workerToken}
            """)
    int releaseLease(@Param("id") String id, @Param("workerToken") String workerToken);
}
