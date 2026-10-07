package cn.wayuwa.wao.mapper;

import java.time.LocalDateTime;
import cn.wayuwa.wao.domain.AutomationJobEntity;
import cn.wayuwa.wao.domain.AutomationRunEntity;
import org.apache.ibatis.annotations.*;

/** 复用自动化表；每日日期主键是不可重领的持久执行凭证。 */
@Mapper
public interface FeishuDailyArchiveMapper {
    @Insert("""
            INSERT IGNORE INTO automation_job
            (id,name,icon,schedule_label,status,description,source,target,steps_json,handler)
            VALUES ('feishu-daily-archive','飞书每日群归档','ri-archive-line','每天上海 00:00','已暂停',
            '只归档前一天，不补扫、不重试','飞书群聊','知识库','[]','feishu-daily-archive')
            """)
    int ensureJob();

    @Select("SELECT * FROM automation_job WHERE id='feishu-daily-archive'")
    AutomationJobEntity job();

    @Select("SELECT * FROM automation_job WHERE id='feishu-daily-archive' FOR UPDATE")
    AutomationJobEntity lockJob();

    @Update("UPDATE automation_job SET status=#{status},next_fire_at=#{next} WHERE id='feishu-daily-archive'")
    int configure(@Param("status") String status, @Param("next") LocalDateTime next);

    @Insert("""
            INSERT IGNORE INTO automation_run (id,job_id,run_time_label,run_at,result,duration_ms,ord)
            VALUES (#{id},'feishu-daily-archive',#{archiveDate},#{runAt},'RUNNING',0,0)
            """)
    int claim(@Param("id") String id, @Param("archiveDate") String archiveDate,
            @Param("runAt") LocalDateTime runAt);

    @Update("""
            UPDATE automation_run SET result=#{result},duration_ms=#{durationMs}
            WHERE id=#{id} AND job_id='feishu-daily-archive' AND result='RUNNING'
            """)
    int complete(@Param("id") String id, @Param("result") String result, @Param("durationMs") int durationMs);

    @Select("SELECT * FROM automation_run WHERE job_id='feishu-daily-archive' ORDER BY id DESC LIMIT 1")
    AutomationRunEntity latestRun();
}
