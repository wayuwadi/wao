DROP TABLE IF EXISTS memory_collection_document;
DROP TABLE IF EXISTS ai_runner_node;

CREATE TABLE ai_runner_node (
    id VARCHAR(32) PRIMARY KEY, name VARCHAR(128) NOT NULL, platform VARCHAR(64), engines VARCHAR(128),
    max_concurrency INT, admin_max_concurrency INT, wake_mac VARCHAR(17), wake_host VARCHAR(255), wake_port INT,
    last_wake_requested_at TIMESTAMP, online_state BOOLEAN, token_hash CHAR(64), script_hash CHAR(64),
    update_requested_at TIMESTAMP, memory_collection_enabled BOOLEAN,
    last_memory_collection_attempt_at TIMESTAMP, last_memory_collected_at TIMESTAMP,
    last_memory_collection_error VARCHAR(1000), cpu_cores INT, cpu_usage_percent DECIMAL(5,1),
    memory_total_bytes BIGINT, memory_available_bytes BIGINT, disk_total_bytes BIGINT,
    disk_free_bytes BIGINT, disk_root VARCHAR(3), system_metrics_collected_at TIMESTAMP,
    log_tail CLOB, log_updated_at TIMESTAMP,
    last_seen_at TIMESTAMP, created_at TIMESTAMP);

CREATE TABLE memory_collection_document (
    id VARCHAR(64) PRIMARY KEY, runner_id VARCHAR(32) NOT NULL, scope_type VARCHAR(24) NOT NULL,
    scope_id VARCHAR(64) NOT NULL, memory_id VARCHAR(64) NOT NULL, content_fingerprint CHAR(64) NOT NULL,
    last_collected_at TIMESTAMP NOT NULL, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_memory_collection_target UNIQUE(runner_id, scope_type, scope_id),
    CONSTRAINT uk_memory_collection_memory UNIQUE(memory_id));

INSERT INTO ai_runner_node (id, name, platform, engines, max_concurrency, online_state, token_hash,
    memory_collection_enabled, last_seen_at, created_at)
VALUES ('rn1', '开发电脑', 'linux', 'codex', 1, TRUE,
    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
