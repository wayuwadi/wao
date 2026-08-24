DROP TABLE IF EXISTS memory_usage_log;
DROP TABLE IF EXISTS memory_outbox;
DROP TABLE IF EXISTS memory_access_grant;
DROP TABLE IF EXISTS memory_attachment;
DROP TABLE IF EXISTS memory_source;
DROP TABLE IF EXISTS memory_revision;
DROP TABLE IF EXISTS memory_document;
DROP TABLE IF EXISTS project;
DROP TABLE IF EXISTS member;
DROP TABLE IF EXISTS org;

CREATE TABLE org (id VARCHAR(32) PRIMARY KEY, name VARCHAR(128) NOT NULL, member_count INT NOT NULL);
CREATE TABLE member (id VARCHAR(32) PRIMARY KEY, name VARCHAR(64) NOT NULL, initial VARCHAR(4) NOT NULL,
    color CHAR(7) NOT NULL, role VARCHAR(32) NOT NULL);
CREATE TABLE project (id VARCHAR(32) PRIMARY KEY, name VARCHAR(128), area VARCHAR(32), status VARCHAR(32),
    health VARCHAR(32), progress INT, owner_id VARCHAR(32), start_date DATE, due_date DATE,
    visibility VARCHAR(32), updated_label VARCHAR(32), goal CLOB, detail_json CLOB, notify_enabled BOOLEAN);

CREATE TABLE memory_document (
    id VARCHAR(64) PRIMARY KEY, scope_type VARCHAR(24) NOT NULL, scope_id VARCHAR(64) NOT NULL,
    category VARCHAR(32) NOT NULL, state VARCHAR(24) NOT NULL, current_published_revision_id VARCHAR(64),
    created_by VARCHAR(32) NOT NULL, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
    archived_at TIMESTAMP NULL);
CREATE TABLE memory_revision (
    id VARCHAR(64) PRIMARY KEY, memory_id VARCHAR(64) NOT NULL, version_no INT NOT NULL,
    state VARCHAR(24) NOT NULL, title VARCHAR(200) NOT NULL, content_md CLOB NOT NULL,
    change_summary VARCHAR(1000), content_fingerprint CHAR(64) NOT NULL, base_revision_id VARCHAR(64),
    evidence_links_json CLOB, author_type VARCHAR(16) NOT NULL, created_by VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL, published_by VARCHAR(32), published_at TIMESTAMP,
    expires_at TIMESTAMP, CONSTRAINT uk_memory_revision_version UNIQUE(memory_id, version_no));
CREATE TABLE memory_source (
    id VARCHAR(64) PRIMARY KEY, revision_id VARCHAR(64) NOT NULL, source_type VARCHAR(24) NOT NULL,
    source_id VARCHAR(128), source_url VARCHAR(1024), excerpt_hash CHAR(64), created_at TIMESTAMP NOT NULL);
CREATE TABLE memory_attachment (
    id VARCHAR(64) PRIMARY KEY, revision_id VARCHAR(64) NOT NULL, file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(160) NOT NULL, byte_size BIGINT NOT NULL, file_content BLOB NOT NULL,
    created_at TIMESTAMP NOT NULL);
CREATE TABLE memory_access_grant (
    id VARCHAR(64) PRIMARY KEY, token_id VARCHAR(32) NOT NULL, scope_type VARCHAR(24) NOT NULL,
    scope_id VARCHAR(64) NOT NULL, can_read BOOLEAN NOT NULL, can_propose BOOLEAN NOT NULL,
    can_direct_write BOOLEAN NOT NULL, created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_memory_access_grant UNIQUE(token_id, scope_type, scope_id));
CREATE TABLE memory_outbox (
    id VARCHAR(64) PRIMARY KEY, event_type VARCHAR(48) NOT NULL, aggregate_id VARCHAR(64) NOT NULL,
    revision_id VARCHAR(64), created_at TIMESTAMP NOT NULL, processed_at TIMESTAMP,
    attempt_count INT NOT NULL, last_error VARCHAR(1000));
CREATE TABLE memory_usage_log (
    id VARCHAR(64) PRIMARY KEY, token_id VARCHAR(32), member_id VARCHAR(32) NOT NULL,
    client_type VARCHAR(24) NOT NULL, scope_type VARCHAR(24) NOT NULL, scope_id VARCHAR(64) NOT NULL,
    action_name VARCHAR(48) NOT NULL, success BOOLEAN NOT NULL, result_count INT NOT NULL,
    latency_ms BIGINT NOT NULL, error_code VARCHAR(64), created_at TIMESTAMP NOT NULL);

INSERT INTO org VALUES ('o1', 'WAO', 1);
INSERT INTO member VALUES ('wg', '测试管理员', '测', '#4358D6', '超级管理员');
INSERT INTO project (id, name, owner_id, notify_enabled) VALUES
    ('p1', '项目一', 'wg', TRUE), ('p-wao', 'WAO', 'wg', TRUE), ('p-private', '其他项目', 'wg', TRUE);
