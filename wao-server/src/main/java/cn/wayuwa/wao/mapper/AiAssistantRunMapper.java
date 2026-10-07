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
                (id, member_id, ai_capability_id, requested_model, reasoning_effort, usage_purpose, web_search_enabled, status, prompt_text,
                 created_at, updated_at)
            VALUES
                (#{id}, #{memberId}, #{aiCapabilityId}, #{requestedModel}, #{reasoningEffort}, #{usagePurpose}, #{webSearchEnabled}, #{status}, #{promptText},
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

    /** 服务端 API worker 跨节点领取一条指定 Provider 的等待任务。 */
    @Update("""
            UPDATE ai_assistant_run
               SET status = 'provider_running', claim_token = #{claimToken},
                   claimed_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = #{id} AND status = 'provider_queued'
            """)
    int claimProviderQueued(@Param("id") String id, @Param("claimToken") String claimToken);

    /** 读取最早一批服务端 API 等待任务，实际归属仍由 CAS 决定。 */
    @Select("""
            SELECT * FROM ai_assistant_run
             WHERE status = 'provider_queued'
             ORDER BY created_at ASC
             LIMIT 8
            """)
    List<AiAssistantRunEntity> selectProviderQueued();

    /** 流式候选仅供预览；旧 worker 和终态不允许覆盖。 */
    @Update("""
            UPDATE ai_assistant_run SET response_text = #{response}, updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = #{id} AND claim_token = #{claimToken} AND status = 'provider_running'
            """)
    int updateProviderPreview(@Param("id") String id, @Param("claimToken") String claimToken,
            @Param("response") String response);

    /** 服务端 Provider 只允许持有本次 claim token 的 worker 写入成功终态。 */
    @Update("""
            UPDATE ai_assistant_run
               SET status = 'succeeded', web_context = NULL, response_text = #{response}, error_text = NULL,
                   prompt_text = NULL, claim_token = NULL,
                   finished_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = #{id} AND claim_token = #{claimToken} AND status = 'provider_running'
            """)
    int completeProviderSucceeded(@Param("id") String id, @Param("claimToken") String claimToken,
            @Param("response") String response);

    /** 服务端 Provider 的失败终态不保存任何部分输出。 */
    @Update("""
            UPDATE ai_assistant_run
               SET status = 'failed', web_context = NULL, response_text = NULL, error_text = #{error},
                   prompt_text = NULL, claim_token = NULL,
                   finished_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = #{id} AND claim_token = #{claimToken} AND status = 'provider_running'
            """)
    int completeProviderFailed(@Param("id") String id, @Param("claimToken") String claimToken,
            @Param("error") String error);

    /** 用数据库时钟按主键顺序读取 API Provider 排队超时候选。 */
    @Select("""
            SELECT id FROM ai_assistant_run
             WHERE status = 'provider_queued'
               AND created_at <= DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL #{timeoutSeconds} SECOND)
             ORDER BY id
            """)
    List<String> findExpiredProviderQueuedIds(@Param("timeoutSeconds") long timeoutSeconds);

    /** 按主键 CAS 终止一条 API Provider 排队任务。 */
    @Update("""
            UPDATE ai_assistant_run
               SET status = 'failed', web_context = NULL, response_text = NULL, error_text = #{reason},
                   prompt_text = NULL, claim_token = NULL,
                   finished_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = #{id} AND status = 'provider_queued'
               AND created_at <= DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL #{timeoutSeconds} SECOND)
            """)
    int expireProviderQueuedById(@Param("id") String id, @Param("timeoutSeconds") long timeoutSeconds,
            @Param("reason") String reason);

    /** 用数据库时钟按主键顺序读取 API Provider 执行超时候选。 */
    @Select("""
            SELECT id FROM ai_assistant_run
             WHERE status = 'provider_running'
               AND claimed_at <= DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL #{timeoutSeconds} SECOND)
             ORDER BY id
            """)
    List<String> findExpiredProviderRunningIds(@Param("timeoutSeconds") long timeoutSeconds);

    /** API Provider 执行超时后按主键令旧 claim token 失效。 */
    @Update("""
            UPDATE ai_assistant_run
               SET status = 'failed', web_context = NULL, response_text = NULL, error_text = #{reason},
                   prompt_text = NULL, claim_token = NULL,
                   finished_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = #{id} AND status = 'provider_running'
               AND claimed_at <= DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL #{timeoutSeconds} SECOND)
            """)
    int expireProviderRunningById(@Param("id") String id, @Param("timeoutSeconds") long timeoutSeconds,
            @Param("reason") String reason);

    /** Provider 页面问答的联网阶段成功后，只保存有界材料并转交所选 Provider。 */
    @Update("""
            UPDATE ai_assistant_run
               SET status = 'provider_queued', web_context = #{webContext}, error_text = NULL,
                   runner_id = NULL, claim_token = NULL, claimed_at = NULL,
                   updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = #{id} AND runner_id = #{runnerId}
               AND claim_token = #{claimToken} AND status = 'web_search_running'
            """)
    int completeWebSearchSucceeded(@Param("id") String id, @Param("runnerId") String runnerId,
            @Param("claimToken") String claimToken, @Param("webContext") String webContext);

    /** 只允许实际持有运行的 Runner 提交一次成功结果。 */
    @Update("""
            UPDATE ai_assistant_run
               SET status = 'succeeded', web_context = NULL, response_text = #{response}, error_text = NULL,
                   prompt_text = NULL, claim_token = NULL,
                   finished_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = #{id} AND runner_id = #{runnerId}
               AND claim_token = #{claimToken} AND status = #{runningStatus}
            """)
    int completeSucceeded(@Param("id") String id, @Param("runnerId") String runnerId,
            @Param("claimToken") String claimToken, @Param("runningStatus") String runningStatus,
            @Param("response") String response);

    /** 仅当前图片任务的领取节点可登记已上传到图床的结果。 */
    @Update("""
            UPDATE ai_assistant_run SET response_text = #{url}, updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = #{id} AND runner_id = #{runnerId} AND claim_token = #{claimToken}
               AND usage_purpose = 'ARTICLE_IMAGE' AND status = 'secure_running'
               AND response_text IS NULL
            """)
    int attachArticleImage(@Param("id") String id, @Param("runnerId") String runnerId,
            @Param("claimToken") String claimToken, @Param("url") String url);

    /** 只允许实际持有运行的 Runner 提交一次失败结果。 */
    @Update("""
            UPDATE ai_assistant_run
               SET status = 'failed', web_context = NULL, response_text = NULL, error_text = #{error},
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
             WHERE id = #{id} AND status IN
                   ('queued', 'running', 'secure_queued', 'secure_running',
                    'web_search_queued', 'web_search_running',
                    'provider_queued', 'provider_running')
            """)
    int failActive(@Param("id") String id, @Param("reason") String reason);

    /** 仅将仍处于旧心跳时间的 running 运行放回队列。 */
    @Update("""
            UPDATE ai_assistant_run
               SET status = CASE
                       WHEN status = 'secure_running' THEN 'secure_queued'
                       WHEN status = 'web_search_running' THEN 'web_search_queued'
                       ELSE 'queued'
                   END,
                   runner_id = NULL, claim_token = NULL,
                   claimed_at = NULL, updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = #{id} AND status IN ('running', 'secure_running', 'web_search_running')
               AND updated_at < #{staleBefore}
            """)
    int requeueStale(@Param("id") String id, @Param("staleBefore") LocalDateTime staleBefore);

    /** 用数据库时钟按主键顺序查找超时的内部只读排队。 */
    @Select("""
            SELECT id
              FROM ai_assistant_run
             WHERE status = 'secure_queued'
               AND (member_id LIKE 'internal-%' OR usage_purpose = 'ARTICLE_IMAGE')
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
               AND (member_id LIKE 'internal-%' OR usage_purpose = 'ARTICLE_IMAGE')
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
               AND (member_id LIKE 'internal-%' OR usage_purpose = 'ARTICLE_IMAGE')
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
               AND (member_id LIKE 'internal-%' OR usage_purpose = 'ARTICLE_IMAGE')
               AND claimed_at <= DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL #{timeoutSeconds} SECOND)
            """)
    int expireReadOnlyRunningById(@Param("id") String id,
            @Param("timeoutSeconds") long timeoutSeconds,
            @Param("reason") String reason);
    /** 只消费当前本机小记执行实例的队列，不领取旧 Runner 或服务端 API 任务。 */
    @Select("SELECT * FROM ai_assistant_run WHERE ai_capability_id=#{capability} AND status='secure_queued' ORDER BY CASE WHEN usage_purpose IN ('NOTE_DISCOVERY_GENERATE','NOTE_DISCOVERY_REVIEW') AND created_at>TIMESTAMPADD(MINUTE,-10,CURRENT_TIMESTAMP(6)) THEN 1 ELSE 0 END,created_at,id LIMIT 100")
    List<AiAssistantRunEntity> selectLocalNoteQueued(@Param("capability") String capability);

    /** 同一执行目录持有独占锁后回收中断任务；不自动重做已经调用过的模型。 */
    @Update("""
            UPDATE ai_assistant_run SET status='failed', response_text=NULL, prompt_text=NULL,
                   claim_token=NULL, error_text='本机 AI 服务中断；原文已保留，请点击重试',
                   finished_at=CURRENT_TIMESTAMP(6), updated_at=CURRENT_TIMESTAMP(6)
             WHERE ai_capability_id=#{capability} AND status='provider_running'
            """)
    int recoverLocalNoteRuns(@Param("capability") String capability);

    /** 本机排队有界收口，避免停机期间积累的旧输入无限等待。 */
    @Update("""
            UPDATE ai_assistant_run SET status='failed', response_text=NULL, prompt_text=NULL,
                   claim_token=NULL, error_text='本机 AI 排队超时；原文已保留，请点击重试',
                   finished_at=CURRENT_TIMESTAMP(6), updated_at=CURRENT_TIMESTAMP(6)
             WHERE ai_capability_id=#{capability} AND status='secure_queued'
               AND created_at <= DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 60 MINUTE)
            """)
    int expireLocalNoteQueue(@Param("capability") String capability);

}
