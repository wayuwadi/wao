-- 成员私有行动计划：长期目标、周期行动、每日真实打卡与定时提醒投递记录。
CREATE TABLE action_plan (
    id               VARCHAR(32)  NOT NULL,
    member_id        VARCHAR(32)  NOT NULL,
    title            VARCHAR(128) NOT NULL,
    vision           VARCHAR(1000) NOT NULL DEFAULT '',
    start_date       DATE         NOT NULL,
    target_date      DATE         NOT NULL,
    reminder_time    TIME         NULL,
    reminder_enabled TINYINT(1)   NOT NULL DEFAULT 0,
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    completed_at     DATETIME(6)  NULL,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_action_plan_member (member_id, status, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE action_plan_item (
    id            VARCHAR(32)  NOT NULL,
    plan_id       VARCHAR(32)  NOT NULL,
    title         VARCHAR(128) NOT NULL,
    weekdays_json VARCHAR(32)  NOT NULL,
    ord           INT          NOT NULL,
    active        TINYINT(1)   NOT NULL DEFAULT 1,
    active_from   DATE         NOT NULL,
    active_until  DATE         NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_action_item_plan (plan_id, active, ord),
    CONSTRAINT fk_action_item_plan FOREIGN KEY (plan_id) REFERENCES action_plan(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE action_checkin (
    id           VARCHAR(32) NOT NULL,
    plan_id      VARCHAR(32) NOT NULL,
    item_id      VARCHAR(32) NOT NULL,
    checkin_date DATE        NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_action_checkin_item_date (item_id, checkin_date),
    KEY idx_action_checkin_plan_date (plan_id, checkin_date),
    CONSTRAINT fk_action_checkin_plan FOREIGN KEY (plan_id) REFERENCES action_plan(id) ON DELETE CASCADE,
    CONSTRAINT fk_action_checkin_item FOREIGN KEY (item_id) REFERENCES action_plan_item(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE action_day_note (
    id         VARCHAR(32)   NOT NULL,
    plan_id    VARCHAR(32)   NOT NULL,
    note_date  DATE          NOT NULL,
    content    VARCHAR(2000) NOT NULL,
    created_at DATETIME(6)   NOT NULL,
    updated_at DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_action_note_plan_date (plan_id, note_date),
    CONSTRAINT fk_action_note_plan FOREIGN KEY (plan_id) REFERENCES action_plan(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE action_reminder_delivery (
    id            VARCHAR(32)  NOT NULL,
    plan_id       VARCHAR(32)  NOT NULL,
    reminder_date DATE         NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    attempts      INT          NOT NULL DEFAULT 0,
    error_message VARCHAR(512) NULL,
    attempted_at  DATETIME(6)  NOT NULL,
    sent_at       DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_action_reminder_plan_date (plan_id, reminder_date),
    KEY idx_action_reminder_retry (status, attempted_at),
    CONSTRAINT fk_action_reminder_plan FOREIGN KEY (plan_id) REFERENCES action_plan(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
