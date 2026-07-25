-- AI 任务执行中心：项目级开关、执行节点、一次性配对码与任务运行记录。
CREATE TABLE ai_project_config (
    project_id           VARCHAR(32)  NOT NULL,
    enabled              BOOLEAN      NOT NULL DEFAULT FALSE,
    repo_url             VARCHAR(512),
    default_branch       VARCHAR(128) NOT NULL DEFAULT 'main',
    workspace_dir        VARCHAR(128),
    preferred_runner_id  VARCHAR(32),
    max_parallel_runs    INT          NOT NULL DEFAULT 2,
    updated_at           DATETIME     NOT NULL,
    PRIMARY KEY (project_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE ai_runner_node (
    id               VARCHAR(32)  NOT NULL,
    name             VARCHAR(128) NOT NULL,
    platform         VARCHAR(64),
    max_concurrency  INT          NOT NULL DEFAULT 1,
    token_hash       CHAR(64)      NOT NULL,
    last_seen_at     DATETIME,
    created_at       DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_runner_token (token_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE ai_runner_pair_code (
    id          VARCHAR(32) NOT NULL,
    code_hash   CHAR(64)    NOT NULL,
    expires_at  DATETIME    NOT NULL,
    used_at     DATETIME,
    created_at  DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_pair_code (code_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE ai_task_run (
    id              VARCHAR(32)  NOT NULL,
    task_id         VARCHAR(32)  NOT NULL,
    project_id      VARCHAR(32)  NOT NULL,
    status          VARCHAR(24)  NOT NULL,
    runner_id       VARCHAR(32),
    requested_by    VARCHAR(32),
    prompt_url      VARCHAR(1024) NOT NULL,
    branch_name     VARCHAR(160),
    result_summary  TEXT,
    session_id      VARCHAR(128),
    exit_code       INT,
    created_at      DATETIME     NOT NULL,
    claimed_at      DATETIME,
    started_at      DATETIME,
    finished_at     DATETIME,
    PRIMARY KEY (id),
    KEY idx_ai_run_status_created (status, created_at),
    KEY idx_ai_run_project_status (project_id, status),
    KEY idx_ai_run_runner_status (runner_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
