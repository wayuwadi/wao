package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.AiAssistantRunEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiAssistantRunMapper extends BaseMapper<AiAssistantRunEntity> {

    /** 内部只读任务的起始时间由数据库统一签发，不信任节点本地时钟。 */
    @Insert("""
            INSERT INTO ai_assistant_run
                (id, member_id, status, prompt_text, created_at, updated_at)
            VALUES
                (#{id}, #{memberId}, #{status}, #{promptText},
                 CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
            """)
    int insertReadOnlyQueued(AiAssistantRunEntity run);

    /** 跨 API 节点条件领取等待中的运行。 */
    @Update("""
            UPDATE ai_assistant_run
               SET status = #{claimedStatus}, runner_id = #{runnerId}, claim_token = #{claimToken},
                   claimed_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = #{id} AND status = #{expectedStatus}
            """)
    int claimQueued(@Param("id") String id, @Param("runnerId") String runnerId,
            @Param("claimToken") String claimToken,
            @Param("expectedStatus") String expectedStatus,
            @Param("claimedStatus") String claimedStatus);

    /** 只允许实际持有运行的 Runner 提交一次成功结果。 */
    @Update("""
            UPDATE ai_assistant_run
               SET status = 'succeeded', response_text = #{response}, error_text = NULL,
                   prompt_text = NULL, claim_token = NULL,
                   finished_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = #{id} AND runner_id = #{runnerId}
               AND claim_token = #{claimToken} AND status = #{runningStatus}
            """)
    int completeSucceeded(@Param("id") String id, @Param("runnerId") String runnerId,
            @Param("claimToken") String claimToken, @Param("runningStatus") String runningStatus,
            @Param("response") String response);

    /** 只允许实际持有运行的 Runner 提交一次失败结果。 */
    @Update("""
            UPDATE ai_assistant_run
               SET status = 'failed', response_text = NULL, error_text = #{error},
                   prompt_text = NULL, claim_token = NULL,
                   finished_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = #{id} AND runner_id = #{runnerId}
               AND claim_token = #{claimToken} AND status = #{runningStatus}
            """)
    int completeFailed(@Param("id") String id, @Param("runnerId") String runnerId,
            @Param("claimToken") String claimToken, @Param("runningStatus") String runningStatus,
            @Param("error") String error);

    /** 等待超时只能把尚未终态的运行转为失败，不覆盖同时到达的成功结果。 */
    @Update("""
            UPDATE ai_assistant_run
               SET status = 'failed', response_text = NULL, error_text = #{reason},
                   prompt_text = NULL, claim_token = NULL,
                   finished_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = #{id} AND status IN ('queued', 'running', 'secure_queued', 'secure_running')
            """)
    int failActive(@Param("id") String id, @Param("reason") String reason);

    /** 仅将仍处于旧心跳时间的 running 运行放回队列。 */
    @Update("""
            UPDATE ai_assistant_run
               SET status = CASE WHEN status = 'secure_running' THEN 'secure_queued' ELSE 'queued' END,
                   runner_id = NULL, claim_token = NULL,
                   claimed_at = NULL, updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = #{id} AND status IN ('running', 'secure_running')
               AND updated_at < #{staleBefore}
            """)
    int requeueStale(@Param("id") String id, @Param("staleBefore") LocalDateTime staleBefore);

    /** 用数据库时钟按主键顺序查找超时的内部只读排队。 */
    @Select("""
            SELECT id
              FROM ai_assistant_run
             WHERE status = 'secure_queued'
               AND member_id LIKE 'internal-%'
               AND created_at <= DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL #{timeoutSeconds} SECOND)
             ORDER BY id
            """)
    List<String> findExpiredReadOnlyQueuedIds(@Param("timeoutSeconds") long timeoutSeconds);

    /** 按主键 CAS 终止一条仍超时的只读排队，并立即清除完整提示。 */
    @Update("""
            UPDATE ai_assistant_run
               SET status = 'failed', response_text = NULL, error_text = #{reason},
                   prompt_text = NULL, runner_id = NULL, claim_token = NULL,
                   finished_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = #{id}
               AND status = 'secure_queued'
               AND member_id LIKE 'internal-%'
               AND created_at <= DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL #{timeoutSeconds} SECOND)
            """)
    int expireReadOnlyQueuedById(@Param("id") String id,
            @Param("timeoutSeconds") long timeoutSeconds,
            @Param("reason") String reason);

    /** 用数据库时钟按主键顺序查找执行超时的内部只读任务。 */
    @Select("""
            SELECT id
              FROM ai_assistant_run
             WHERE status = 'secure_running'
               AND member_id LIKE 'internal-%'
               AND claimed_at <= DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL #{timeoutSeconds} SECOND)
             ORDER BY id
            """)
    List<String> findExpiredReadOnlyRunningIds(@Param("timeoutSeconds") long timeoutSeconds);

    /** 按主键 CAS 终止一条仍超时的只读任务，旧 Runner 回传会被拒绝。 */
    @Update("""
            UPDATE ai_assistant_run
               SET status = 'failed', response_text = NULL, error_text = #{reason},
                   prompt_text = NULL, runner_id = NULL, claim_token = NULL,
                   finished_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = #{id}
               AND status = 'secure_running'
               AND member_id LIKE 'internal-%'
               AND claimed_at <= DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL #{timeoutSeconds} SECOND)
            """)
    int expireReadOnlyRunningById(@Param("id") String id,
            @Param("timeoutSeconds") long timeoutSeconds,
            @Param("reason") String reason);
}
