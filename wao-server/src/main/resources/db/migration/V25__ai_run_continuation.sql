-- 保存待原 AI 会话消费的用户补充输入。
ALTER TABLE ai_task_run ADD COLUMN pending_input TEXT NULL COMMENT '等待原 AI 会话消费的用户补充输入';
