-- V93：每天用项目负责人最近 24 小时的真实浏览器指标创建性能治理 AI Run。
-- next_fire_at 直接落到下一个上海时间 01:30；双节点仍由 AutomationService 条件更新原子领取。
INSERT INTO automation_job
    (id, name, icon, schedule_label, cron_expr, status, last_run_label, description, source, target,
     steps_json, next_fire_at, ord, handler)
VALUES
    ('job-http-performance-ai-run', 'HTTP 请求性能夜间 AI 巡检', 'ri-speed-up-line',
     '每日 01:30', '0 30 1 * * *', '正常', '尚未运行',
     '读取 Wa All in One 项目负责人最近 24 小时的真实浏览器 HTTP 指标，有数据时创建任务并进入开发 AI Run。',
     'HTTP 请求监控 · 最近 24 小时', 'Wa All in One · AI Run',
     JSON_ARRAY('读取项目负责人最近 24 小时真实指标', '把汇总与最慢接口写入任务目标', '创建 Task 并进入项目 AI Run'),
     DATE_ADD(DATE_ADD(CURDATE(), INTERVAL IF(CURTIME() < '01:30:00', 0, 1) DAY), INTERVAL 90 MINUTE),
     110, 'http-performance-ai-run');
