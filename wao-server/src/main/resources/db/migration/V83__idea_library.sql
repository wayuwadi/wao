CREATE TABLE idea (
    id              VARCHAR(32)  NOT NULL,
    title           VARCHAR(256) NOT NULL,
    raw_idea        TEXT,
    problem_text    TEXT,
    target_outcome  TEXT,
    priority        CHAR(2)      NOT NULL,
    stage           VARCHAR(32)  NOT NULL,
    created_by      VARCHAR(32)  NOT NULL,
    owner_id        VARCHAR(32)  NOT NULL,
    project_id      VARCHAR(32),
    detail_json     JSON,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_idea_stage_updated (stage, updated_at),
    KEY idx_idea_owner_stage (owner_id, stage),
    KEY idx_idea_project (project_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE idea_artifact (
    id          VARCHAR(32)  NOT NULL,
    idea_id     VARCHAR(32)  NOT NULL,
    kind        VARCHAR(32)  NOT NULL,
    doc_id      VARCHAR(32)  NOT NULL,
    title       VARCHAR(256) NOT NULL,
    created_by  VARCHAR(32)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_idea_artifact_idea_created (idea_id, created_at),
    KEY idx_idea_artifact_doc (doc_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE idea_task_link (
    id            VARCHAR(32) NOT NULL,
    idea_id       VARCHAR(32) NOT NULL,
    task_id       VARCHAR(32) NOT NULL,
    required_mvp  BOOLEAN     NOT NULL DEFAULT TRUE,
    ord           INT         NOT NULL DEFAULT 1,
    created_by    VARCHAR(32) NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idea_task (idea_id, task_id),
    KEY idx_idea_task_task (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE idea_iteration (
    id                   VARCHAR(32) NOT NULL,
    idea_id              VARCHAR(32) NOT NULL,
    task_id              VARCHAR(32) NOT NULL,
    ai_run_id            VARCHAR(32),
    objective            TEXT        NOT NULL,
    acceptance_criteria  TEXT,
    status               VARCHAR(24) NOT NULL,
    feedback             TEXT,
    result_summary       TEXT,
    report_url           VARCHAR(1024),
    created_by           VARCHAR(32) NOT NULL,
    created_at           DATETIME(6) NOT NULL,
    finished_at          DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_idea_iteration_idea_created (idea_id, created_at),
    KEY idx_idea_iteration_run (ai_run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE idea_research_run (
    id             VARCHAR(32) NOT NULL,
    idea_id        VARCHAR(32) NOT NULL,
    member_id      VARCHAR(32) NOT NULL,
    runner_id      VARCHAR(32),
    claim_token    VARCHAR(64),
    status         VARCHAR(24) NOT NULL,
    prompt_text    MEDIUMTEXT,
    response_text  MEDIUMTEXT,
    error_text     VARCHAR(2000),
    doc_id         VARCHAR(32),
    created_at     DATETIME(6) NOT NULL,
    claimed_at     DATETIME(6),
    finished_at    DATETIME(6),
    updated_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_idea_research_status_created (status, created_at),
    KEY idx_idea_research_idea_created (idea_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
