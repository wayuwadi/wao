package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.TaskEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 任务表 Mapper。 */
@Mapper
public interface TaskMapper extends BaseMapper<TaskEntity> {

    /** 锁住任务行，保证“升级为项目”在重试或并发点击时只创建一个项目。 */
    @Select("SELECT * FROM task WHERE id=#{id} FOR UPDATE")
    TaskEntity selectByIdForUpdate(String id);

    /**
     * 读取录入时间最早、尚未开始且具备真实 AI 工作流的任务。
     *
     * <p>进行中、待验证和收集中的任务都不应被无人值守调度擅自重跑。
     */
    @Select("""
            SELECT t.*
            FROM task t
            JOIN ai_project_config c ON c.project_id = t.project_id
            WHERE t.status = '待办'
              AND c.enabled = TRUE
              AND c.repo_url IS NOT NULL
              AND TRIM(c.repo_url) <> ''
              AND NOT EXISTS (
                SELECT 1
                FROM ai_task_run_task rt
                JOIN ai_task_run r ON r.id = rt.run_id
                WHERE rt.task_id = t.id
                  AND r.sub_id IS NULL
                  AND r.status IN ('queued', 'claimed', 'running')
              )
            ORDER BY t.created_at IS NULL, t.created_at, t.id
            LIMIT 1
            """)
    TaskEntity selectOldestAutoRunCandidate();

    /** 飞书原消息编辑只窄更新来源字段，避免覆盖并发保存的任务结构化详情。 */
    @Update("UPDATE task SET title = #{title}, description = #{description} WHERE id = #{id}")
    int updateChatSourceFields(@Param("id") String id, @Param("title") String title,
            @Param("description") String description);
}
