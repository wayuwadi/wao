-- 成员主动选择国际节日，并为稳定节日代码设置每年重复的飞书私聊提醒。
CREATE TABLE holiday_reminder (
    id            VARCHAR(32)  NOT NULL,
    member_id     VARCHAR(32)  NOT NULL,
    holiday_code  VARCHAR(64)  NOT NULL,
    holiday_name  VARCHAR(64)  NOT NULL,
    reminder_time TIME         NULL,
    enabled       TINYINT(1)   NOT NULL DEFAULT 0,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_holiday_reminder_member_code (member_id, holiday_code),
    KEY idx_holiday_reminder_due (enabled, reminder_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE holiday_reminder_delivery (
    id            VARCHAR(32)  NOT NULL,
    reminder_id   VARCHAR(32)  NOT NULL,
    event_date    DATE         NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    attempts      INT          NOT NULL DEFAULT 0,
    error_message VARCHAR(512) NULL,
    attempted_at  DATETIME(6)  NOT NULL,
    sent_at       DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_holiday_delivery_reminder_date (reminder_id, event_date),
    KEY idx_holiday_delivery_retry (status, attempted_at),
    CONSTRAINT fk_holiday_delivery_reminder FOREIGN KEY (reminder_id)
        REFERENCES holiday_reminder(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
