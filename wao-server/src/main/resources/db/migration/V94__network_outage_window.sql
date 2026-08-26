-- 已知每日断网窗口默认由 system_setting 的代码回落提供；这里只调整已存在的真实巡检计划。
-- 使用新 migration 更新既有行，不修改已经执行的 V93。
UPDATE automation_job
SET schedule_label = '每日 06:30',
    cron_expr = '0 30 6 * * *',
    next_fire_at = DATE_ADD(
        DATE_ADD(CURDATE(), INTERVAL IF(CURTIME() < '06:30:00', 0, 1) DAY),
        INTERVAL 390 MINUTE)
WHERE id = 'job-http-performance-ai-run';
