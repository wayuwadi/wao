package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.domain.AiTaskRunTaskEntity;
import cn.wayuwa.wao.domain.ReviewableTaskRunRow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiTaskRunMapper extends BaseMapper<AiTaskRunEntity> {

    /**
     * 用数据库条件更新抢占一条排队运行，防止多 API 节点同时把同一次运行交给多个 Runner。
     * 待续输入也参与比较，避免领取与用户补充并发时静默丢掉新输入。
     */
    @Update("""
            UPDATE ai_task_run
            SET status = 'running', runner_id = #{runnerId}, claimed_at = #{claimedAt},
                started_at = #{claimedAt}, pending_input = NULL,
                latest_progress = #{progress}, progress_updated_at = #{claimedAt}
            WHERE id = #{runId} AND status = 'queued'
              AND (pending_input = #{pendingInput}
                   OR (pending_input IS NULL AND #{pendingInput} IS NULL))
            """)
    int claimQueuedRun(@Param("runId") String runId,
            @Param("runnerId") String runnerId,
            @Param("pendingInput") String pendingInput,
            @Param("progress") String progress,
            @Param("claimedAt") LocalDateTime claimedAt);

    /** 保存一次运行与一条任务的稳定顺序关系。 */
    @Insert("INSERT INTO ai_task_run_task (run_id, task_id, ord) VALUES (#{runId}, #{taskId}, #{ord})")
    int insertTaskLink(@Param("runId") String runId, @Param("taskId") String taskId, @Param("ord") int ord);

    /** 批量读取运行关联的任务，避免运行列表逐条查询。 */
    @Select("""
            <script>
            SELECT run_id AS runId, task_id AS taskId, ord
            FROM ai_task_run_task
            WHERE run_id IN
            <foreach collection="runIds" item="runId" open="(" separator="," close=")">
              #{runId}
            </foreach>
            ORDER BY run_id, ord
            </script>
            """)
    List<AiTaskRunTaskEntity> selectTaskLinks(@Param("runIds") List<String> runIds);

    /** 指定任务的最近运行档案，包括它作为合并运行非首条任务的情况。 */
    @Select("""
            SELECT r.*
            FROM ai_task_run r
            JOIN ai_task_run_task rt ON rt.run_id = r.id
            WHERE rt.task_id = #{taskId}
            ORDER BY r.created_at DESC
            LIMIT #{limit}
            """)
    List<AiTaskRunEntity> selectTaskRuns(@Param("taskId") String taskId, @Param("limit") int limit);

    /**
     * 批量读取每条任务最近创建的 AI Run；包含活动态，避免旧失败运行在新运行期间仍被误报为需重试。
     */
    @Select("""
            <script>
            SELECT rt.task_id AS review_task_id, r.id AS run_id, r.status, r.delivery_stage,
                   r.result_summary, r.finished_at
            FROM ai_task_run_task rt
            JOIN ai_task_run r ON r.id = rt.run_id
            WHERE rt.task_id IN
            <foreach collection="taskIds" item="taskId" open="(" separator="," close=")">
              #{taskId}
            </foreach>
              AND NOT EXISTS (
                SELECT 1
                FROM ai_task_run_task newer_rt
                JOIN ai_task_run newer ON newer.id = newer_rt.run_id
                WHERE newer_rt.task_id = rt.task_id
                  AND (newer.created_at &gt; r.created_at
                    OR (newer.created_at = r.created_at AND newer.id &gt; r.id))
              )
            </script>
            """)
    List<ReviewableTaskRunRow> selectLatestRunsForTasks(@Param("taskIds") List<String> taskIds);

    /** 一条主任务是否已属于任何活动运行；合并入队必须整体判断，不能静默漏项。 */
    @Select("""
            SELECT COUNT(*)
            FROM ai_task_run r
            JOIN ai_task_run_task rt ON rt.run_id = r.id
            WHERE rt.task_id = #{taskId}
              AND r.sub_id IS NULL
              AND r.status IN ('queued', 'claimed', 'running')
            """)
    long countActiveMainRunsForTask(@Param("taskId") String taskId);

    /** 最近一次创建或结束 AI 任务的时间；长任务结束后也要重新开始计算 12 小时空闲窗口。 */
    @Select("""
            SELECT CASE
                WHEN MAX(finished_at) IS NULL OR MAX(created_at) >= MAX(finished_at) THEN MAX(created_at)
                ELSE MAX(finished_at)
            END
            FROM ai_task_run
            """)
    LocalDateTime latestActivityAt();

    /** 已结束运行总数，供完整历史分页。 */
    @Select("SELECT COUNT(*) FROM ai_task_run WHERE status NOT IN ('queued', 'claimed', 'running')")
    long countFinishedRuns();

    /** 首页读取全部活动运行；数量受 Runner 并发槽位约束，不能因历史展示上限而漏掉。 */
    @Select("""
            SELECT * FROM ai_task_run
            WHERE status IN ('queued', 'claimed', 'running')
            ORDER BY created_at DESC
            """)
    List<AiTaskRunEntity> selectActiveRuns();

    /** 首页只读取最近的已结束运行；完整档案由分页历史接口按需提供。 */
    @Select("""
            SELECT * FROM ai_task_run
            WHERE status NOT IN ('queued', 'claimed', 'running')
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<AiTaskRunEntity> selectRecentFinishedRuns(@Param("limit") int limit);

    /** 按最新时间读取一页已结束运行，不依赖全局分页插件。 */
    @Select("""
            SELECT * FROM ai_task_run
            WHERE status NOT IN ('queued', 'claimed', 'running')
            ORDER BY created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<AiTaskRunEntity> selectFinishedRunPage(@Param("offset") long offset, @Param("limit") int limit);
}
