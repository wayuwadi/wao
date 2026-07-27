-- 保存真实的 Wake-on-LAN 配置和最近一次唤醒请求，供离线执行电脑远程唤醒。
ALTER TABLE ai_runner_node
    ADD COLUMN wake_mac VARCHAR(17) NULL AFTER admin_max_concurrency,
    ADD COLUMN wake_host VARCHAR(255) NULL AFTER wake_mac,
    ADD COLUMN wake_port INT NULL DEFAULT 9 AFTER wake_host,
    ADD COLUMN last_wake_requested_at DATETIME(6) NULL AFTER wake_port;
