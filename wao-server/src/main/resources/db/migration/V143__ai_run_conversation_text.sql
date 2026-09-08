-- 公开对话正文与首页摘要分开；旧消息保留，无法补回历史上已被截断的内容。
ALTER TABLE ai_run_progress MODIFY COLUMN content TEXT NOT NULL;
-- 同一秒内的多条消息保留真实先后顺序，ID 仅作为游标的稳定兜底。
ALTER TABLE ai_run_progress MODIFY COLUMN created_at DATETIME(6) NOT NULL;
