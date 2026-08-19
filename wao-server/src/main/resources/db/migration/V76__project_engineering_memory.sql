-- 项目工程记忆与 AI Run 读写引用：独立于成员私有 personal_memory。
CREATE TABLE project_memory (
    id                    VARCHAR(32)  NOT NULL,
    project_id            VARCHAR(32)  NOT NULL,
    category              VARCHAR(24)  NOT NULL,
    title                 VARCHAR(200) NOT NULL,
    content               TEXT         NOT NULL,
    source_task_id        VARCHAR(32),
    source_run_id         VARCHAR(32),
    evidence_links_json   TEXT,
    content_fingerprint   CHAR(64)     NOT NULL,
    created_by            VARCHAR(32),
    created_at            DATETIME     NOT NULL,
    updated_at            DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_memory_fingerprint (project_id, content_fingerprint),
    KEY idx_project_memory_updated (project_id, updated_at),
    KEY idx_project_memory_category (project_id, category),
    CONSTRAINT fk_project_memory_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    CONSTRAINT fk_project_memory_task FOREIGN KEY (source_task_id) REFERENCES task(id) ON DELETE SET NULL,
    CONSTRAINT fk_project_memory_run FOREIGN KEY (source_run_id) REFERENCES ai_task_run(id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE ai_run_memory_read (
    id           VARCHAR(32) NOT NULL,
    run_id       VARCHAR(32) NOT NULL,
    memory_id    VARCHAR(32) NOT NULL,
    rank_order   INT         NOT NULL,
    created_at   DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_run_memory_read (run_id, memory_id),
    KEY idx_ai_run_memory_read_memory (memory_id),
    CONSTRAINT fk_ai_run_memory_read_run FOREIGN KEY (run_id) REFERENCES ai_task_run(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_run_memory_read_memory FOREIGN KEY (memory_id) REFERENCES project_memory(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE ai_run_memory_write (
    id           VARCHAR(32) NOT NULL,
    run_id       VARCHAR(32) NOT NULL,
    memory_id    VARCHAR(32) NOT NULL,
    rank_order   INT         NOT NULL,
    created_at   DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_run_memory_write (run_id, memory_id),
    KEY idx_ai_run_memory_write_memory (memory_id),
    CONSTRAINT fk_ai_run_memory_write_run FOREIGN KEY (run_id) REFERENCES ai_task_run(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_run_memory_write_memory FOREIGN KEY (memory_id) REFERENCES project_memory(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
