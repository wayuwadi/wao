package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.SystemPeriodicReportEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** 系统周期报告快照 Mapper。 */
@Mapper
public interface SystemPeriodicReportMapper extends BaseMapper<SystemPeriodicReportEntity> {
    /** 以周期唯一键幂等写入；重新补齐时更新快照而不是增加重复报告。 */
    @Insert("""
            INSERT INTO system_periodic_report
              (id, period_type, period_key, period_start, period_end, project_total, task_total,
               open_task_total, ai_run_total, task_created_count, task_completed_count, ai_run_count,
               ai_run_success_count, ai_run_failure_count, ai_run_metered_count, total_tokens,
               task_sources_json, run_sources_json, generated_at)
            VALUES
              (#{id}, #{periodType}, #{periodKey}, #{periodStart}, #{periodEnd}, #{projectTotal}, #{taskTotal},
               #{openTaskTotal}, #{aiRunTotal}, #{taskCreatedCount}, #{taskCompletedCount}, #{aiRunCount},
               #{aiRunSuccessCount}, #{aiRunFailureCount}, #{aiRunMeteredCount}, #{totalTokens},
               #{taskSourcesJson}, #{runSourcesJson}, #{generatedAt})
            ON DUPLICATE KEY UPDATE
              period_start=VALUES(period_start), period_end=VALUES(period_end), project_total=VALUES(project_total),
              task_total=VALUES(task_total), open_task_total=VALUES(open_task_total), ai_run_total=VALUES(ai_run_total),
              task_created_count=VALUES(task_created_count), task_completed_count=VALUES(task_completed_count),
              ai_run_count=VALUES(ai_run_count), ai_run_success_count=VALUES(ai_run_success_count),
              ai_run_failure_count=VALUES(ai_run_failure_count), ai_run_metered_count=VALUES(ai_run_metered_count),
              total_tokens=VALUES(total_tokens), task_sources_json=VALUES(task_sources_json),
              run_sources_json=VALUES(run_sources_json), generated_at=VALUES(generated_at)
            """)
    int upsert(SystemPeriodicReportEntity report);
}
