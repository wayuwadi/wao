package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.AiTaskRunEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

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
}
