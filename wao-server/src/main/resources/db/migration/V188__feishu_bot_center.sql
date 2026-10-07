-- 保留 Webhook 稳定身份和所有现有通知引用；旧配置默认不增加收集能力。
ALTER TABLE feishu_webhook_config
    ADD COLUMN capture_kind VARCHAR(8) NOT NULL DEFAULT 'NONE',
    ADD COLUMN mention_id VARCHAR(128) NULL,
    ADD COLUMN mention_name VARCHAR(64) NULL,
    ADD COLUMN chat_id VARCHAR(128) NULL,
    ADD COLUMN app_id VARCHAR(128) NULL,
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE feishu_quick_collect_event ADD COLUMN webhook_id VARCHAR(32) NULL,
    ADD COLUMN target_version VARCHAR(80) NULL,
    ADD INDEX idx_collect_webhook_updated (webhook_id, updated_at);

CREATE TABLE feishu_bot_send (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    webhook_id VARCHAR(32) NOT NULL,
    kind VARCHAR(8) NOT NULL,
    status VARCHAR(8) NOT NULL,
    error_code VARCHAR(64) NULL,
    occurred_at DATETIME(6) NOT NULL,
    INDEX idx_bot_send_recent (webhook_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE feishu_bot_inbox (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    event_id VARCHAR(128) NULL,
    message_id VARCHAR(128) NOT NULL,
    app_id VARCHAR(128) NOT NULL,
    webhook_id VARCHAR(32) NULL,
    capture_kind VARCHAR(8) NOT NULL,
    payload_ciphertext MEDIUMBLOB NULL,
    payload_nonce VARBINARY(12) NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    lease_token VARCHAR(32) NULL,
    available_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    error_code VARCHAR(64) NULL,
    INDEX idx_bot_inbox_pending (status, available_at),
    INDEX idx_bot_inbox_activity (webhook_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
