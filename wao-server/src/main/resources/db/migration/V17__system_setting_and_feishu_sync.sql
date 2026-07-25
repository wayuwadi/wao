-- 系统级配置（全局唯一一份）：飞书通知 webhook、飞书群聊同步的应用凭据与映射关系。
-- 敏感值（app secret / webhook 地址）以 VaultCryptoService 的 AES-256-GCM 加密后存 cfg_value，
-- 密文带 `enc:` 前缀，接口只回脱敏形态，不回明文。
CREATE TABLE system_setting (
    cfg_key    VARCHAR(64)  NOT NULL,
    cfg_value  TEXT,
    updated_at DATETIME,
    PRIMARY KEY (cfg_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 自动化任务挂真实执行器：handler 非空的任务由对应的服务端实现执行，而非模拟运行。
ALTER TABLE automation_job ADD COLUMN handler VARCHAR(64) NULL;

-- 内置「飞书群聊消息同步知识库」任务：每天 23:59 把当天 00:00–23:59 的群聊记录沉淀成一篇文档。
-- 默认暂停，等在「自动化 → 配置」里填完应用凭据与目标知识库后再启用。
INSERT INTO automation_job
    (id, name, icon, schedule_label, cron_expr, status, last_run_label, description, source, target,
     steps_json, next_fire_at, ord, handler)
VALUES
    ('job-feishu-chat-sync', '飞书群聊消息同步知识库', 'ri-feishu-line', '每日 23:59', '0 59 23 * * *',
     '已暂停', '尚未运行',
     '每天 23:59 拉取当天 00:00 起的群聊记录，识别发言人后写成一篇文档存进知识库；聊天图片转存到本地图床再引用。',
     '飞书群聊', '知识库',
     '["读取群聊当天的消息历史","把发言人 ID 映射成成员名","聊天图片转存图床","生成当天的记录文档写入知识库"]',
     NULL, 100, 'feishu-chat-sync');
