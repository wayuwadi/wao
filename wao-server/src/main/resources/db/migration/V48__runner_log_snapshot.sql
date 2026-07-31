-- 保存 Runner 最近一段已脱敏诊断日志，供 AI 控制台按需查看；不上传完整历史日志。
ALTER TABLE ai_runner_node
    ADD COLUMN log_tail MEDIUMTEXT NULL AFTER update_requested_at,
    ADD COLUMN log_updated_at DATETIME NULL AFTER log_tail;
