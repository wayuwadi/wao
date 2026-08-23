-- 今日节奏的成员级面板设置；时间块和精力补给仍保留在旧版 member_preference 中。
CREATE TABLE workday_rhythm_setting (
    member_id                 VARCHAR(32)  NOT NULL,
    follow_alternate_week     TINYINT(1)   NOT NULL DEFAULT 1,
    document_kb_id            VARCHAR(32)  NULL,
    document_folder_id        VARCHAR(32)  NULL,
    document_template_id      VARCHAR(32)  NULL,
    document_title_pattern    VARCHAR(128) NOT NULL DEFAULT '{date} 每日工作记录',
    tianren_enabled           TINYINT(1)   NOT NULL DEFAULT 1,
    tianren_required          TINYINT(1)   NOT NULL DEFAULT 0,
    created_at                DATETIME(6)  NOT NULL,
    updated_at                DATETIME(6)  NOT NULL,
    PRIMARY KEY (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 成员自己维护的每日必做模板。系统项的 kind 不可变，但名称、时间和通知均可编辑。
CREATE TABLE workday_rhythm_checklist_item (
    id                    VARCHAR(32)  NOT NULL,
    member_id             VARCHAR(32)  NOT NULL,
    kind                  VARCHAR(20)  NOT NULL,
    system_key            VARCHAR(20)  NULL,
    category              VARCHAR(12)  NOT NULL,
    title                 VARCHAR(80)  NOT NULL,
    schedule_type         VARCHAR(20)  NOT NULL,
    weekdays_json         VARCHAR(32)  NOT NULL DEFAULT '[]',
    start_time            TIME         NOT NULL,
    due_time              TIME         NOT NULL,
    due_next_day          TINYINT(1)   NOT NULL DEFAULT 0,
    active                TINYINT(1)   NOT NULL DEFAULT 1,
    ord                   INT          NOT NULL DEFAULT 0,
    notify_direct         TINYINT(1)   NOT NULL DEFAULT 0,
    notify_chat           TINYINT(1)   NOT NULL DEFAULT 0,
    chat_id               VARCHAR(128) NULL,
    chat_name             VARCHAR(100) NULL,
    notify_webhook        TINYINT(1)   NOT NULL DEFAULT 0,
    webhook_id            VARCHAR(32)  NULL,
    webhook_name          VARCHAR(100) NULL,
    reminder_time         TIME         NULL,
    reminder_next_day     TINYINT(1)   NOT NULL DEFAULT 0,
    deleted_at            DATETIME(6)  NULL,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rhythm_item_member_system (member_id, system_key),
    KEY idx_rhythm_item_member (member_id, active, ord)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 每日实例存一份不受后续模板修改影响的快照。
CREATE TABLE workday_rhythm_checklist_occurrence (
    id                    VARCHAR(40)  NOT NULL,
    item_id               VARCHAR(32)  NOT NULL,
    member_id             VARCHAR(32)  NOT NULL,
    occurrence_date       DATE         NOT NULL,
    kind                  VARCHAR(20)  NOT NULL,
    category              VARCHAR(12)  NOT NULL,
    title                 VARCHAR(80)  NOT NULL,
    start_time            TIME         NOT NULL,
    due_time              TIME         NOT NULL,
    due_next_day          TINYINT(1)   NOT NULL DEFAULT 0,
    reminder_time         TIME         NULL,
    reminder_next_day     TINYINT(1)   NOT NULL DEFAULT 0,
    notify_direct         TINYINT(1)   NOT NULL DEFAULT 0,
    notify_chat           TINYINT(1)   NOT NULL DEFAULT 0,
    chat_id               VARCHAR(128) NULL,
    chat_name             VARCHAR(100) NULL,
    notify_webhook        TINYINT(1)   NOT NULL DEFAULT 0,
    webhook_id            VARCHAR(32)  NULL,
    webhook_name          VARCHAR(100) NULL,
    completed_at          DATETIME(6)  NULL,
    completed_on_time     TINYINT(1)   NULL,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rhythm_occurrence_item_date (item_id, occurrence_date),
    KEY idx_rhythm_occurrence_member_date (member_id, occurrence_date),
    KEY idx_rhythm_occurrence_due (completed_at, occurrence_date, reminder_time),
    CONSTRAINT fk_rhythm_occurrence_item FOREIGN KEY (item_id)
        REFERENCES workday_rhythm_checklist_item(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 当天的工作日覆盖和唯一每日文档绑定。NULL 表示沿用自动工作日判断。
CREATE TABLE workday_rhythm_day (
    member_id          VARCHAR(32) NOT NULL,
    rhythm_date        DATE        NOT NULL,
    workday_override   TINYINT(1)  NULL,
    document_id        VARCHAR(32) NULL,
    created_at         DATETIME(6) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (member_id, rhythm_date),
    UNIQUE KEY uk_rhythm_day_document (document_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 通知通道分开去重；失败可重试，任意通道成功后都不会重复投递。
CREATE TABLE workday_rhythm_reminder_delivery (
    id                VARCHAR(40)  NOT NULL,
    occurrence_id     VARCHAR(40)  NOT NULL,
    channel           VARCHAR(16)  NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    attempts          INT          NOT NULL DEFAULT 0,
    error_message     VARCHAR(512) NULL,
    attempted_at      DATETIME(6)  NOT NULL,
    sent_at           DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rhythm_delivery_occurrence_channel (occurrence_id, channel),
    KEY idx_rhythm_delivery_retry (status, attempted_at),
    CONSTRAINT fk_rhythm_delivery_occurrence FOREIGN KEY (occurrence_id)
        REFERENCES workday_rhythm_checklist_occurrence(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
