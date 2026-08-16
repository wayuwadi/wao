package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.AiTaskRunEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiTaskRunMapper extends BaseMapper<AiTaskRunEntity> {

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
