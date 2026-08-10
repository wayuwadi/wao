CREATE TABLE ai_assistant_run (
    id VARCHAR(32) PRIMARY KEY,
    member_id VARCHAR(32) NOT NULL,
    runner_id VARCHAR(32) NULL,
    status VARCHAR(16) NOT NULL,
    prompt_text MEDIUMTEXT NULL,
    response_text MEDIUMTEXT NULL,
    error_text TEXT NULL,
    created_at DATETIME(6) NOT NULL,
    claimed_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_ai_assistant_member_status (member_id, status),
    INDEX idx_ai_assistant_status_created (status, created_at),
    CONSTRAINT fk_ai_assistant_runner FOREIGN KEY (runner_id) REFERENCES ai_runner_node(id) ON DELETE SET NULL
);
