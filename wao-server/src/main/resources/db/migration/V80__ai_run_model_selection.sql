-- 固化每次 Codex AI Run 的模型与推理强度，避免执行节点的全局配置改变实际运行参数。
ALTER TABLE ai_task_run
    ADD COLUMN requested_model VARCHAR(128) NULL AFTER pending_input,
    ADD COLUMN reasoning_effort VARCHAR(16) NULL AFTER requested_model;
