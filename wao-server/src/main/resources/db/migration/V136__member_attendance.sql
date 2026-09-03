CREATE TABLE attendance_connector (
    member_id             VARCHAR(32)  NOT NULL,
    provider              VARCHAR(16)  NOT NULL DEFAULT 'MOKA',
    username              VARCHAR(160) NULL,
    vault_item_id         VARCHAR(32)  NULL,
    enabled               TINYINT(1)   NOT NULL DEFAULT 0,
    remote_employee_id    VARCHAR(64)  NULL,
    session_ciphertext    BLOB         NULL,
    session_nonce         VARBINARY(12) NULL,
    status                VARCHAR(24)  NOT NULL DEFAULT 'UNCONFIGURED',
    last_synced_at        DATETIME(6)  NULL,
    last_error            VARCHAR(512) NULL,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    PRIMARY KEY (member_id),
    KEY idx_attendance_connector_enabled (enabled, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE attendance_day (
    member_id             VARCHAR(32)  NOT NULL,
    attendance_date       DATE         NOT NULL,
    remote_clock_in_at    DATETIME(6)  NULL,
    remote_clock_out_at   DATETIME(6)  NULL,
    remote_day_type       VARCHAR(16)  NULL,
    remote_anomaly        VARCHAR(255) NULL,
    manual_clock_in_at    DATETIME(6)  NULL,
    manual_clock_out_at   DATETIME(6)  NULL,
    manual_day_type       VARCHAR(16)  NULL,
    manual_override       TINYINT(1)   NOT NULL DEFAULT 0,
    synced_at             DATETIME(6)  NULL,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    PRIMARY KEY (member_id, attendance_date),
    KEY idx_attendance_day_date (attendance_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

ALTER TABLE workday_rhythm_checklist_occurrence
    ADD COLUMN completion_source VARCHAR(16) NULL AFTER completed_on_time;
