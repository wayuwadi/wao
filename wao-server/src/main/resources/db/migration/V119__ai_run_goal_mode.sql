-- Codex 原生目标模式：固化派工选择，并保存 app-server 回传的真实目标状态。
ALTER TABLE ai_task_run
    ADD COLUMN goal_mode BOOLEAN NOT NULL DEFAULT FALSE AFTER plan_mode,
    ADD COLUMN goal_objective VARCHAR(1000) NULL AFTER goal_mode,
    ADD COLUMN goal_status VARCHAR(24) NULL AFTER goal_objective,
    ADD COLUMN goal_tokens_used BIGINT NULL AFTER goal_status,
    ADD COLUMN goal_time_used_seconds BIGINT NULL AFTER goal_tokens_used;
