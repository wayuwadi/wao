-- 群聊同步任务使用 Remix Icon 中稳定存在的聊天图标，避免前端出现空白图标。
UPDATE automation_job
SET icon = 'ri-chat-3-line'
WHERE id = 'job-feishu-chat-sync';
