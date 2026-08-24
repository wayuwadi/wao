CREATE TABLE system_periodic_report (
    id                    VARCHAR(32) NOT NULL,
    period_type           VARCHAR(16) NOT NULL,
    period_key            VARCHAR(24) NOT NULL,
    period_start          DATE        NOT NULL,
    period_end            DATE        NOT NULL,
    project_total         INT         NOT NULL DEFAULT 0,
    task_total            INT         NOT NULL DEFAULT 0,
    open_task_total       INT         NOT NULL DEFAULT 0,
    ai_run_total          INT         NOT NULL DEFAULT 0,
    task_created_count    INT         NOT NULL DEFAULT 0,
    task_completed_count  INT         NOT NULL DEFAULT 0,
    ai_run_count          INT         NOT NULL DEFAULT 0,
    ai_run_success_count  INT         NOT NULL DEFAULT 0,
    ai_run_failure_count  INT         NOT NULL DEFAULT 0,
    ai_run_metered_count  INT         NOT NULL DEFAULT 0,
    total_tokens          BIGINT      NOT NULL DEFAULT 0,
    task_sources_json     JSON        NOT NULL,
    run_sources_json      JSON        NOT NULL,
    generated_at          DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_system_report_period (period_type, period_key),
    KEY idx_system_report_generated (generated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

ALTER TABLE task
    ADD KEY idx_task_created_at (created_at),
    ADD KEY idx_task_completed_at (completed_at);

ALTER TABLE ai_task_run
    ADD KEY idx_ai_run_created_at (created_at);
