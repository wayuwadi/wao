CREATE TABLE feishu_api_usage (
    usage_day DATE NOT NULL,
    endpoint VARCHAR(200) NOT NULL,
    request_count BIGINT NOT NULL DEFAULT 0,
    first_observed_at DATETIME(6) NOT NULL,
    last_observed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (usage_day, endpoint)
) COMMENT='WAO 飞书客户端实际请求计数，不代表租户账单';

INSERT INTO shared_chat_sync_state (id, cursor_epoch_seconds)
VALUES ('quick-realtime', UNIX_TIMESTAMP()), ('quick-edits', UNIX_TIMESTAMP());
