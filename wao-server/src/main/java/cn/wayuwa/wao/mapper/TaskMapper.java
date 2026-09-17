package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.TaskEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

/** 任务表 Mapper。 */
@Mapper
public interface TaskMapper extends BaseMapper<TaskEntity> {
    /** 事务内按稳定顺序锁定源任务与目标任务。 */
    @Select("SELECT * FROM task WHERE id = #{id} FOR UPDATE")
    TaskEntity lockById(String id);

    /** 只更新内嵌详情，保留并发更新的其它任务属性。 */
    @Update("UPDATE task SET detail_json = #{detail} WHERE id = #{id}")
    int updateDetail(@Param("id") String id, @Param("detail") String detail);

    /** 有未结束 AI 执行时禁止迁移归属，避免正在运行的提示与回写指向错误任务。 */
    @Select("SELECT COUNT(*) FROM ai_task_run WHERE task_id IN (#{source}, #{target}) AND finished_at IS NULL")
    int countUnfinishedRuns(@Param("source") String source, @Param("target") String target);

    /**
     * 按录入时间读取所选项目、所选安全状态中最早且具备真实 AI 工作流的任务。
     *
     * <p>允许的来源状态由服务层限制；AI 进行中、待验证和已完成不会进入无人值守候选。
     */
    @Select("""
            <script>
            SELECT t.*
            FROM task t
            JOIN ai_project_config c ON c.project_id = t.project_id
            WHERE t.project_id = #{projectId}
              AND t.status IN
              <foreach collection="taskStatuses" item="status" open="(" separator="," close=")">
                #{status}
              </foreach>
              AND c.enabled = TRUE
              AND c.repo_url IS NOT NULL
              AND TRIM(c.repo_url) &lt;&gt; ''
              AND NOT EXISTS (
                SELECT 1
                FROM ai_task_run_task rt
                JOIN ai_task_run r ON r.id = rt.run_id
                WHERE rt.task_id = t.id
                  AND r.sub_id IS NULL
                  AND r.status IN ('queued', 'claimed', 'running')
              )
            ORDER BY t.created_at IS NULL, t.created_at, t.id
            LIMIT #{limit}
            </script>
            """)
    List<TaskEntity> selectOldestAutoRunCandidates(@Param("projectId") String projectId,
            @Param("taskStatuses") List<String> taskStatuses, @Param("limit") int limit);

    /** 飞书原消息编辑只窄更新来源字段，避免覆盖并发保存的任务结构化详情。 */
    @Update("UPDATE task SET title = #{title}, description = #{description} WHERE id = #{id}")
    int updateChatSourceFields(@Param("id") String id, @Param("title") String title,
            @Param("description") String description);
}
