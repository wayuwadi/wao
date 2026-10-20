CREATE TABLE project (id VARCHAR(32) PRIMARY KEY);
CREATE TABLE task (
    id VARCHAR(32) PRIMARY KEY,
    created_at DATETIME(3),
    completed_at DATETIME(3)
);
CREATE TABLE ai_task_run (
    id VARCHAR(32) PRIMARY KEY,
    created_at DATETIME(3),
    finished_at DATETIME(3)
);
CREATE TABLE doc (
    page_type VARCHAR(20) DEFAULT 'document', drawing_id VARCHAR(32),
    id VARCHAR(32) PRIMARY KEY,
    created_at DATETIME(3),
    updated_at DATETIME(3),
    deleted_at DATETIME(3)
);
CREATE TABLE memory_revision (
    id VARCHAR(64) PRIMARY KEY,
    created_at DATETIME(3)
);
CREATE TABLE shared_chat_message (
    id VARCHAR(32) PRIMARY KEY,
    created_at DATETIME(6)
);
CREATE TABLE http_request_metric (
    id BIGINT PRIMARY KEY,
    started_at DATETIME(3)
);
CREATE TABLE product_update_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id VARCHAR(32) NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    scope VARCHAR(64) NOT NULL,
    commit_subject VARCHAR(255) NOT NULL,
    committed_at DATETIME(3) NOT NULL,
    UNIQUE (commit_sha)
);
CREATE TABLE ai_project_config (
    project_id VARCHAR(32) PRIMARY KEY,
    repo_url VARCHAR(512),
    updated_at DATETIME(3)
);
