DROP TABLE IF EXISTS ai_run_memory_write;
DROP TABLE IF EXISTS ai_run_memory_read;
DROP TABLE IF EXISTS project_memory;

CREATE TABLE project_memory (
    id VARCHAR(32) PRIMARY KEY,
    project_id VARCHAR(32) NOT NULL,
    category VARCHAR(24) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    source_task_id VARCHAR(32),
    source_run_id VARCHAR(32),
    evidence_links_json TEXT,
    content_fingerprint CHAR(64) NOT NULL,
    created_by VARCHAR(32),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_project_memory_fingerprint UNIQUE (project_id, content_fingerprint)
);

CREATE TABLE ai_run_memory_read (
    id VARCHAR(32) PRIMARY KEY,
    run_id VARCHAR(32) NOT NULL,
    memory_id VARCHAR(32) NOT NULL,
    rank_order INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_run_memory_read UNIQUE (run_id, memory_id)
);

CREATE TABLE ai_run_memory_write (
    id VARCHAR(32) PRIMARY KEY,
    run_id VARCHAR(32) NOT NULL,
    memory_id VARCHAR(32) NOT NULL,
    rank_order INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_run_memory_write UNIQUE (run_id, memory_id)
);
