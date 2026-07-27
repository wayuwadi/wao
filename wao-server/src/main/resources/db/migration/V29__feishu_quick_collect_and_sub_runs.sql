-- 子任务级 AI Run：一次运行可以只针对主任务下的某一条子任务。
-- 主任务运行时 sub_id 为 NULL；分享给 AI 的清单会把已经派过工的子任务过滤掉，避免重复算力。
ALTER TABLE ai_task_run
    ADD COLUMN sub_id VARCHAR(64) NULL COMMENT '本次运行针对的子任务标识；主任务运行时为 NULL';

CREATE INDEX idx_ai_run_task_sub ON ai_task_run (task_id, sub_id);

-- 群聊快速收集：新增「收集中」状态，不需要改表结构（task.status 是自由文本），
-- 这里只登记一条说明，避免后来的人以为状态集合还停在 V16。
