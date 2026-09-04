-- 每卦一次的群卡片投递；快照不含应用凭据或 Webhook 地址。
CREATE INDEX idx_tianren_reading_date_id ON tianren_daily_reading (reading_date, id);

CREATE TABLE tianren_notification (
    reading_id VARCHAR(32) NOT NULL PRIMARY KEY,
    state VARCHAR(16) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    target_id VARCHAR(128) NOT NULL,
    target_name VARCHAR(128) NOT NULL,
    payload MEDIUMTEXT NOT NULL,
    message_id VARCHAR(128) NULL,
    attempt_token VARCHAR(36) NULL,
    message VARCHAR(240) NOT NULL DEFAULT '',
    updated_at DATETIME NOT NULL,
    CONSTRAINT fk_tianren_notification_reading FOREIGN KEY (reading_id)
        REFERENCES tianren_daily_reading(id) ON DELETE CASCADE,
    INDEX idx_tianren_notification_state (state, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
