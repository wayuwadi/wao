-- 自动化域关系建模：任务 / 运行记录，替换 V3 中通用 payload 的 job 表。

DROP TABLE IF EXISTS job;

CREATE TABLE automation_job (
    id            VARCHAR(32)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    icon          VARCHAR(64)  NOT NULL DEFAULT 'ri-flashlight-line',
    schedule_label VARCHAR(64),
    cron_expr     VARCHAR(64),
    status        VARCHAR(16)  NOT NULL DEFAULT '正常',
    last_run_label VARCHAR(128),
    description   VARCHAR(512),
    source        VARCHAR(128),
    target        VARCHAR(128),
    steps_json    JSON,
    next_fire_at  DATETIME,
    ord           INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE automation_run (
    id            VARCHAR(32)  NOT NULL,
    job_id        VARCHAR(32)  NOT NULL,
    run_time_label VARCHAR(64),
    result        VARCHAR(64),
    executor_id   VARCHAR(32),
    duration_ms   INT,
    ord           INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_run_job (job_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
