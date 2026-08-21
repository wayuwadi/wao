-- 固化单次 Codex AI Run 的 Plan 工作流选择；旧运行保持直接执行。
ALTER TABLE ai_task_run
    ADD COLUMN plan_mode BOOLEAN NOT NULL DEFAULT FALSE AFTER reasoning_effort;
