# 飞书机器人注册表与可靠接收

新增迁移 V188；沿用现有 `feishu_webhook_config` 和 `feishu_quick_collect_event`，不重新分配 Webhook ID 或改写历史引用。迁移为加法，已执行后不可修改。

| 结构 | 新增字段或含义 |
| --- | --- |
| feishu_webhook_config | capture_kind、mention_id、mention_name、chat_id、app_id、revision；管理 CAS 与接收路由 |
| feishu_quick_collect_event | webhook_id、target_version；关联机器人与来源同步时的目标版本 |
| feishu_bot_send | webhook_id、kind、status、occurred_at、error_code；实际发送审计，错误为安全代码 |
| feishu_bot_inbox | 加密事件 payload_ciphertext/payload_nonce、app_id、webhook_id、capture_kind、event_id、message_id、status、attempts、available_at、lease_token、error_code 与时间戳 |

Inbox ID 按应用、消息 ID 与事件载荷摘要确定；原始认证 Token 不入队。处理认领签发独立 lease_token，事务内锁行并核对有效租约；创建和 DONE 同事务提交。重试或完成同时校验租约；最多六次，崩溃后的超时 PROCESSING 可重新认领，第六次超时转 FAILED。完成清空加密载荷，已完成空载记录七天后清理；失败保留以便诊断。

删除旧名称绑定时保留 `feishu.bot-center.legacy-task-retired`／`legacy-note-retired` 设置，防止旧名称兼容路由重新启动已删除入口。新建明确绑定仍可启用替代机器人。
