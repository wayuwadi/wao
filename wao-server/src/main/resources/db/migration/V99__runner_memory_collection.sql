-- Runner 定时把本机 Codex 记忆整理成机器/应用候选；原始文件不上传。
ALTER TABLE ai_runner_node
    ADD COLUMN memory_collection_enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER update_requested_at,
    ADD COLUMN last_memory_collection_attempt_at DATETIME(3) NULL AFTER memory_collection_enabled,
    ADD COLUMN last_memory_collected_at DATETIME(3) NULL AFTER last_memory_collection_attempt_at,
    ADD COLUMN last_memory_collection_error VARCHAR(1000) NULL AFTER last_memory_collected_at;

CREATE TABLE memory_collection_document (
    id                  VARCHAR(64)  NOT NULL,
    runner_id           VARCHAR(32)  NOT NULL,
    scope_type          VARCHAR(24)  NOT NULL,
    scope_id            VARCHAR(64)  NOT NULL,
    memory_id           VARCHAR(64)  NOT NULL,
    content_fingerprint CHAR(64)     NOT NULL,
    last_collected_at   DATETIME(3)  NOT NULL,
    created_at          DATETIME(3)  NOT NULL,
    updated_at          DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_collection_target (runner_id, scope_type, scope_id),
    UNIQUE KEY uk_memory_collection_memory (memory_id),
    KEY idx_memory_collection_scope (scope_type, scope_id, updated_at),
    CONSTRAINT fk_memory_collection_runner FOREIGN KEY (runner_id)
        REFERENCES ai_runner_node (id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_collection_document FOREIGN KEY (memory_id)
        REFERENCES memory_document (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

