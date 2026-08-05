-- 自动化运行记录保存真实开始时间，不再把“今天 HH:mm”这种相对文案永久写进数据库。
ALTER TABLE automation_run
    ADD COLUMN run_at DATETIME NULL COMMENT '真实开始时间（Asia/Shanghai）' AFTER run_time_label;

-- 既有飞书同步结果自带业务日期，可与旧文案里的时分组合，恢复截图中已显示错误的历史记录。
UPDATE automation_run
SET run_at = STR_TO_DATE(CONCAT(
        REGEXP_SUBSTR(result, '[0-9]{4}-[0-9]{2}-[0-9]{2}'), ' ',
        REGEXP_SUBSTR(run_time_label, '[0-9]{2}:[0-9]{2}')
    ), '%Y-%m-%d %H:%i')
WHERE job_id = 'job-feishu-chat-sync'
  AND REGEXP_SUBSTR(result, '[0-9]{4}-[0-9]{2}-[0-9]{2}') IS NOT NULL
  AND REGEXP_SUBSTR(run_time_label, '[0-9]{2}:[0-9]{2}') IS NOT NULL;
