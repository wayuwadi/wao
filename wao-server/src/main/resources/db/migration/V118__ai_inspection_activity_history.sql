-- 为真实 AI Run 固化来源分类，并把 HTTP 巡检自动化记录关联到它创建的运行。
ALTER TABLE ai_task_run
    ADD COLUMN activity_type VARCHAR(32) NOT NULL DEFAULT 'FEATURE_DEVELOPMENT' AFTER status,
    ADD KEY idx_ai_run_activity_terminal (activity_type, status, finished_at);

ALTER TABLE automation_run
    ADD COLUMN ai_run_id VARCHAR(32) NULL AFTER result,
    ADD KEY idx_automation_run_ai_run (ai_run_id);

-- 旧版 HTTP 巡检成功文案固定为“成功 · 已创建 AI Run <id>”；只处理这个固定任务和前缀。
UPDATE automation_run
SET ai_run_id = TRIM(SUBSTRING_INDEX(
        SUBSTRING(result, CHAR_LENGTH('成功 · 已创建 AI Run ') + 1), '；', 1))
WHERE job_id = 'job-http-performance-ai-run'
  AND result LIKE '成功 · 已创建 AI Run %';

UPDATE ai_task_run AS run
JOIN automation_run AS automation ON automation.ai_run_id = run.id
SET run.activity_type = 'AI_INSPECTION'
WHERE automation.job_id = 'job-http-performance-ai-run';
