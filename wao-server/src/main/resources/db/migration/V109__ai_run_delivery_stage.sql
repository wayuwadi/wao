-- AI Run 的代码与部署阶段独立于进程状态，等待批量部署期间仍保持活动运行。
ALTER TABLE ai_task_run
    ADD COLUMN delivery_stage VARCHAR(32) NOT NULL DEFAULT 'coding' AFTER status;

UPDATE ai_task_run
SET delivery_stage = CASE
    WHEN status = 'succeeded' THEN 'deployment_succeeded'
    WHEN status = 'failed' THEN 'code_failed'
    ELSE 'coding'
END;
