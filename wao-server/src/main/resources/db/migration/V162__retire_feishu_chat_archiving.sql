-- 停止聊天记录同步，保留历史记录、机器人凭据及任务收集配置。
UPDATE automation_job SET status = '已暂停', next_fire_at = NULL,
    last_run_label = '已停用：聊天记录不再与飞书同步'
WHERE handler IN ('feishu-chat-sync', 'shared-chat-nightly-sync');
