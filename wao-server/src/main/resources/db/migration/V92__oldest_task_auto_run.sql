CREATE TABLE ai_task_auto_run_state (
    id              VARCHAR(32)  NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    idle_hours      INT          NOT NULL DEFAULT 2,
    next_check_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_checked_at DATETIME(6)  NULL,
    last_outcome    VARCHAR(500) NULL,
    last_run_id     VARCHAR(32)  NULL,
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT chk_task_auto_run_idle_hours CHECK (idle_hours BETWEEN 1 AND 168),
    CONSTRAINT fk_task_auto_run_last_run FOREIGN KEY (last_run_id) REFERENCES ai_task_run(id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO ai_task_auto_run_state (id, enabled, idle_hours)
VALUES ('oldest-task', TRUE, 2);
