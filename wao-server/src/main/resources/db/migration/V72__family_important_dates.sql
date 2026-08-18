-- 成员维护的家庭生日、纪念日等重要日期；按公历月日每年出现在 WAO 日历。
CREATE TABLE family_important_date (
    id                   VARCHAR(32)  NOT NULL,
    member_id            VARCHAR(32)  NOT NULL,
    name                 VARCHAR(64)  NOT NULL,
    event_date           DATE         NOT NULL,
    note                 VARCHAR(200) NULL,
    reminder_enabled     TINYINT(1)   NOT NULL DEFAULT 0,
    reminder_days_before INT          NOT NULL DEFAULT 0,
    reminder_time        TIME         NULL,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_family_important_date_member (member_id, event_date),
    KEY idx_family_important_date_due (reminder_enabled, reminder_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- occurrence_date 是本次重要日实际发生日期；提前提醒也以它作为年度幂等键。
CREATE TABLE family_important_date_delivery (
    id                VARCHAR(32)  NOT NULL,
    important_date_id VARCHAR(32)  NOT NULL,
    occurrence_date   DATE         NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    attempts          INT          NOT NULL DEFAULT 0,
    error_message     VARCHAR(512) NULL,
    attempted_at      DATETIME(6)  NOT NULL,
    sent_at           DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_family_important_date_delivery (important_date_id, occurrence_date),
    KEY idx_family_important_date_delivery_retry (status, attempted_at),
    CONSTRAINT fk_family_important_date_delivery FOREIGN KEY (important_date_id)
        REFERENCES family_important_date(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
