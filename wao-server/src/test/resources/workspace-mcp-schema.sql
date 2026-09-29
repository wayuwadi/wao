DROP TABLE IF EXISTS workspace_mcp_token;
DROP TABLE IF EXISTS doc;
DROP TABLE IF EXISTS kb_folder;
DROP TABLE IF EXISTS kb;
DROP TABLE IF EXISTS system_setting;

CREATE TABLE system_setting (
    cfg_key VARCHAR(128) PRIMARY KEY, cfg_value TEXT, updated_at TIMESTAMP NOT NULL
);
CREATE TABLE workspace_mcp_token (
    id VARCHAR(32) PRIMARY KEY, member_id VARCHAR(32) NOT NULL,
    name VARCHAR(80) NOT NULL, token_prefix VARCHAR(20) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE, can_write BOOLEAN NOT NULL,
    can_publish_hosted_page BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL, last_used_at TIMESTAMP NULL, revoked_at TIMESTAMP NULL
);
CREATE TABLE kb (
    directory_json VARCHAR(100000), id VARCHAR(32) PRIMARY KEY,
    name VARCHAR(128) NOT NULL, icon VARCHAR(64), color CHAR(7),
    project_id VARCHAR(32), auto BOOLEAN NOT NULL, description VARCHAR(512),
    group_id VARCHAR(32), perm VARCHAR(16), ord INT, notes BOOLEAN NOT NULL,
    deleted_at TIMESTAMP NULL, deleted_by VARCHAR(32) NULL
);
CREATE TABLE kb_folder (
    id VARCHAR(32) PRIMARY KEY, kb_id VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL, ord INT, deleted_at TIMESTAMP NULL,
    cascade_del BOOLEAN NOT NULL
);
CREATE TABLE doc (
    id VARCHAR(32) PRIMARY KEY, kb_id VARCHAR(32) NOT NULL,
    folder_id VARCHAR(32) NOT NULL, title VARCHAR(256) NOT NULL,
    author_id VARCHAR(32) NOT NULL, auto BOOLEAN NOT NULL,
    created_label VARCHAR(32), updated_label VARCHAR(32),
    blocks_json CLOB, links_json CLOB, ord INT,
    created_at TIMESTAMP NULL, updated_at TIMESTAMP NULL, rev INT NOT NULL,
    archived_at TIMESTAMP NULL, deleted_at TIMESTAMP NULL,
    deleted_by VARCHAR(32) NULL, cascade_del BOOLEAN NOT NULL
);
INSERT INTO kb (id, name, icon, color, auto, description, group_id, perm, ord, notes)
VALUES ('kb-project', '项目知识', 'ri-book-line', '#5B6FD8', TRUE, '测试项目知识', 'g1', '成员可编辑', 90, FALSE);
