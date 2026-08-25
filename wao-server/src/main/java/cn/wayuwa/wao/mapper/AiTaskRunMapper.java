package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.domain.AiTaskRunTaskEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiTaskRunMapper extends BaseMapper<AiTaskRunEntity> {

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

    /** 按最新时间读取一页已结束运行，不依赖全局分页插件。 */
    @Select("""
            SELECT * FROM ai_task_run
            WHERE status NOT IN ('queued', 'claimed', 'running')
            ORDER BY created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<AiTaskRunEntity> selectFinishedRunPage(@Param("offset") long offset, @Param("limit") int limit);
}
