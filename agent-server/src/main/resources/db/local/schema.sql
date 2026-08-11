-- 本地 H2 同时模拟 WAO 共享账号/Session 表；生产环境由 WAO migration 管理这些表。
CREATE TABLE IF NOT EXISTS member (
    id VARCHAR(32) PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    initial VARCHAR(4) NOT NULL,
    color CHAR(7) NOT NULL,
    role VARCHAR(32) NOT NULL
);

CREATE TABLE IF NOT EXISTS account (
    id VARCHAR(36) PRIMARY KEY,
    member_id VARCHAR(32) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_local_account_member FOREIGN KEY (member_id) REFERENCES member(id)
);

MERGE INTO member (id, name, initial, color, role) KEY(id)
VALUES ('demo', '本地演示用户', '演', '#6556D8', 'owner');
MERGE INTO account (id, member_id, status, created_at, updated_at) KEY(id)
VALUES ('acc-demo', 'demo', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

CREATE TABLE IF NOT EXISTS agent_workspace (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    owner_account_id VARCHAR(36) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_local_agent_workspace_owner FOREIGN KEY (owner_account_id) REFERENCES account(id)
);

CREATE TABLE IF NOT EXISTS agent_workspace_member (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    role VARCHAR(24) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_local_agent_workspace_member UNIQUE (workspace_id, account_id),
    CONSTRAINT fk_local_agent_member_workspace FOREIGN KEY (workspace_id) REFERENCES agent_workspace(id),
    CONSTRAINT fk_local_agent_member_account FOREIGN KEY (account_id) REFERENCES account(id)
);

CREATE TABLE IF NOT EXISTS agent_content_draft (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    title VARCHAR(200) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    content CLOB NOT NULL,
    status VARCHAR(24) NOT NULL,
    version INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_local_agent_draft_workspace FOREIGN KEY (workspace_id) REFERENCES agent_workspace(id)
);

CREATE TABLE IF NOT EXISTS agent_run (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    requested_by_account_id VARCHAR(36) NOT NULL,
    topic VARCHAR(200) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    progress INT NOT NULL,
    result_summary CLOB,
    error_message VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_local_agent_run_workspace FOREIGN KEY (workspace_id) REFERENCES agent_workspace(id),
    CONSTRAINT fk_local_agent_run_account FOREIGN KEY (requested_by_account_id) REFERENCES account(id)
);

CREATE INDEX IF NOT EXISTS idx_agent_draft_workspace ON agent_content_draft(workspace_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_agent_run_workspace ON agent_run(workspace_id, created_at);
CREATE INDEX IF NOT EXISTS idx_agent_run_status ON agent_run(status, updated_at);

-- 与 db/migration/V2__platform_publishing.sql 对应，改动必须两边同步。
CREATE TABLE IF NOT EXISTS agent_platform_account (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    platform VARCHAR(32) NOT NULL,
    platform_user_id VARCHAR(128) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    avatar_url VARCHAR(512),
    status VARCHAR(24) NOT NULL,
    scope VARCHAR(512),
    credential_cipher CLOB NOT NULL,
    access_token_expires_at TIMESTAMP,
    refresh_token_expires_at TIMESTAMP,
    last_error VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_local_agent_platform_account UNIQUE (workspace_id, platform, platform_user_id),
    CONSTRAINT fk_local_agent_platform_account_ws FOREIGN KEY (workspace_id) REFERENCES agent_workspace(id)
);

CREATE TABLE IF NOT EXISTS agent_media_asset (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    byte_size BIGINT NOT NULL,
    storage_path VARCHAR(512) NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_local_agent_media_workspace FOREIGN KEY (workspace_id) REFERENCES agent_workspace(id)
);

CREATE TABLE IF NOT EXISTS agent_publish_task (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    requested_by_account_id VARCHAR(36) NOT NULL,
    platform VARCHAR(32) NOT NULL,
    platform_account_id VARCHAR(36),
    media_asset_id VARCHAR(36),
    topic VARCHAR(200) NOT NULL,
    title VARCHAR(200),
    content CLOB,
    tags_json VARCHAR(1000),
    status VARCHAR(32) NOT NULL,
    progress INT NOT NULL,
    node_id VARCHAR(64),
    platform_item_id VARCHAR(128),
    error_message VARCHAR(1000),
    version INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP,
    structure_json CLOB,
    cover_text VARCHAR(200),
    source_hotspot_id VARCHAR(36),
    CONSTRAINT fk_local_agent_publish_workspace FOREIGN KEY (workspace_id) REFERENCES agent_workspace(id),
    CONSTRAINT fk_local_agent_publish_requester FOREIGN KEY (requested_by_account_id) REFERENCES account(id),
    CONSTRAINT fk_local_agent_publish_platform_account FOREIGN KEY (platform_account_id) REFERENCES agent_platform_account(id),
    CONSTRAINT fk_local_agent_publish_media FOREIGN KEY (media_asset_id) REFERENCES agent_media_asset(id)
);

-- 与 db/migration/V3__hotspot_and_studio.sql 对应，改动必须两边同步。
CREATE TABLE IF NOT EXISTS agent_hotspot (
    id VARCHAR(36) PRIMARY KEY,
    source VARCHAR(32) NOT NULL,
    external_id VARCHAR(128) NOT NULL,
    title VARCHAR(500) NOT NULL,
    summary VARCHAR(1000),
    url VARCHAR(1000),
    heat BIGINT,
    rank_no INT NOT NULL,
    first_seen_at TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_local_agent_hotspot UNIQUE (source, external_id)
);

CREATE TABLE IF NOT EXISTS agent_workspace_profile (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    niche VARCHAR(200),
    audience VARCHAR(200),
    tone VARCHAR(200),
    avoid VARCHAR(500),
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_local_agent_profile_workspace UNIQUE (workspace_id),
    CONSTRAINT fk_local_agent_profile_workspace FOREIGN KEY (workspace_id) REFERENCES agent_workspace(id)
);

CREATE TABLE IF NOT EXISTS agent_topic_suggestion (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    hotspot_id VARCHAR(36),
    platform VARCHAR(32) NOT NULL,
    topic VARCHAR(200) NOT NULL,
    angle VARCHAR(1000),
    reason VARCHAR(1000),
    fit_score INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_local_agent_suggestion_workspace FOREIGN KEY (workspace_id) REFERENCES agent_workspace(id),
    CONSTRAINT fk_local_agent_suggestion_hotspot FOREIGN KEY (hotspot_id) REFERENCES agent_hotspot(id)
);

CREATE INDEX IF NOT EXISTS idx_agent_hotspot_seen ON agent_hotspot(last_seen_at);
CREATE INDEX IF NOT EXISTS idx_agent_hotspot_source_rank ON agent_hotspot(source, rank_no);
CREATE INDEX IF NOT EXISTS idx_agent_suggestion_workspace ON agent_topic_suggestion(workspace_id, created_at);
CREATE INDEX IF NOT EXISTS idx_agent_platform_account_ws ON agent_platform_account(workspace_id, platform);
CREATE INDEX IF NOT EXISTS idx_agent_media_workspace ON agent_media_asset(workspace_id, created_at);
CREATE INDEX IF NOT EXISTS idx_agent_publish_workspace ON agent_publish_task(workspace_id, created_at);
CREATE INDEX IF NOT EXISTS idx_agent_publish_status ON agent_publish_task(status, updated_at);

CREATE TABLE IF NOT EXISTS SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL PRIMARY KEY,
    SESSION_ID CHAR(36) NOT NULL UNIQUE,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100)
);

CREATE INDEX IF NOT EXISTS SPRING_SESSION_IX2 ON SPRING_SESSION(EXPIRY_TIME);
CREATE INDEX IF NOT EXISTS SPRING_SESSION_IX3 ON SPRING_SESSION(PRINCIPAL_NAME);

CREATE TABLE IF NOT EXISTS SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BINARY LARGE OBJECT NOT NULL,
    PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
