-- WAO 统一记忆中心：三种权限域、不可变版本、来源、附件、设备授权与可靠派生事件。
CREATE TABLE memory_document (
    id                            VARCHAR(64)  NOT NULL,
    scope_type                    VARCHAR(24)  NOT NULL,
    scope_id                      VARCHAR(64)  NOT NULL,
    category                      VARCHAR(32)  NOT NULL,
    state                         VARCHAR(24)  NOT NULL,
    current_published_revision_id VARCHAR(64),
    created_by                    VARCHAR(32)  NOT NULL,
    created_at                    DATETIME(3)  NOT NULL,
    updated_at                    DATETIME(3)  NOT NULL,
    archived_at                   DATETIME(3),
    PRIMARY KEY (id),
    KEY idx_memory_document_scope (scope_type, scope_id, state, updated_at),
    KEY idx_memory_document_published (current_published_revision_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE memory_revision (
    id                  VARCHAR(64)  NOT NULL,
    memory_id           VARCHAR(64)  NOT NULL,
    version_no          INT          NOT NULL,
    state               VARCHAR(24)  NOT NULL,
    title               VARCHAR(256) NOT NULL,
    content_md          MEDIUMTEXT   NOT NULL,
    change_summary      VARCHAR(1000),
    content_fingerprint CHAR(64)     NOT NULL,
    base_revision_id    VARCHAR(64),
    evidence_links_json JSON,
    author_type         VARCHAR(16)  NOT NULL,
    created_by          VARCHAR(32)  NOT NULL,
    created_at          DATETIME(3)  NOT NULL,
    published_by        VARCHAR(32),
    published_at        DATETIME(3),
    expires_at          DATETIME(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_revision_version (memory_id, version_no),
    KEY idx_memory_revision_state (memory_id, state, created_at),
    CONSTRAINT fk_memory_revision_document FOREIGN KEY (memory_id)
        REFERENCES memory_document (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE memory_source (
    id            VARCHAR(64)  NOT NULL,
    revision_id   VARCHAR(64)  NOT NULL,
    source_type   VARCHAR(24)  NOT NULL,
    source_id     VARCHAR(128),
    source_url    VARCHAR(1024),
    excerpt_hash  CHAR(64),
    created_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_memory_source_revision (revision_id),
    CONSTRAINT fk_memory_source_revision FOREIGN KEY (revision_id)
        REFERENCES memory_revision (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE memory_attachment (
    id            VARCHAR(64)  NOT NULL,
    revision_id   VARCHAR(64)  NOT NULL,
    file_name     VARCHAR(255) NOT NULL,
    content_type  VARCHAR(160) NOT NULL,
    byte_size     BIGINT       NOT NULL,
    file_content  LONGBLOB     NOT NULL,
    created_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_memory_attachment_revision (revision_id),
    CONSTRAINT fk_memory_attachment_revision FOREIGN KEY (revision_id)
        REFERENCES memory_revision (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE memory_access_grant (
    id               VARCHAR(64) NOT NULL,
    token_id         VARCHAR(32) NOT NULL,
    scope_type       VARCHAR(24) NOT NULL,
    scope_id         VARCHAR(64) NOT NULL,
    can_read         TINYINT(1)  NOT NULL DEFAULT 1,
    can_propose      TINYINT(1)  NOT NULL DEFAULT 0,
    can_direct_write TINYINT(1)  NOT NULL DEFAULT 0,
    created_at       DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_access_grant (token_id, scope_type, scope_id),
    KEY idx_memory_access_grant_scope (scope_type, scope_id),
    CONSTRAINT fk_memory_access_grant_token FOREIGN KEY (token_id)
        REFERENCES memory_access_token (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE memory_outbox (
    id            VARCHAR(64)  NOT NULL,
    event_type    VARCHAR(48)  NOT NULL,
    aggregate_id  VARCHAR(64)  NOT NULL,
    revision_id   VARCHAR(64),
    created_at    DATETIME(3)  NOT NULL,
    processed_at  DATETIME(3),
    attempt_count INT          NOT NULL DEFAULT 0,
    last_error    VARCHAR(1000),
    PRIMARY KEY (id),
    KEY idx_memory_outbox_pending (processed_at, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE memory_usage_log (
    id           VARCHAR(64)  NOT NULL,
    token_id     VARCHAR(32),
    member_id    VARCHAR(32)  NOT NULL,
    client_type  VARCHAR(24)  NOT NULL,
    scope_type   VARCHAR(24)  NOT NULL,
    scope_id     VARCHAR(64)  NOT NULL,
    action_name  VARCHAR(48)  NOT NULL,
    success      TINYINT(1)   NOT NULL,
    result_count INT          NOT NULL DEFAULT 0,
    latency_ms   BIGINT       NOT NULL DEFAULT 0,
    error_code   VARCHAR(64),
    created_at   DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_memory_usage_scope (scope_type, scope_id, created_at),
    KEY idx_memory_usage_member (member_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 个人记忆保留原 id，并迁移为立即发布的 v1；附件绑定到不可变版本。
INSERT IGNORE INTO memory_document
    (id, scope_type, scope_id, category, state, current_published_revision_id,
     created_by, created_at, updated_at)
SELECT id, 'personal', member_id, category, 'active', CONCAT('legacy-personal-', id),
       member_id, created_at, updated_at
  FROM personal_memory;

INSERT IGNORE INTO memory_revision
    (id, memory_id, version_no, state, title, content_md, change_summary, content_fingerprint,
     evidence_links_json, author_type, created_by, created_at, published_by, published_at)
SELECT CONCAT('legacy-personal-', id), id, 1, 'published', title, COALESCE(content, ''),
       '从个人记忆迁移', SHA2(LOWER(TRIM(COALESCE(content, ''))), 256), JSON_ARRAY(),
       CASE WHEN capture_mode = 'ai' THEN 'ai' ELSE 'human' END,
       member_id, created_at, member_id, updated_at
  FROM personal_memory;

INSERT IGNORE INTO memory_attachment
    (id, revision_id, file_name, content_type, byte_size, file_content, created_at)
SELECT CONCAT('legacy-attachment-', id), CONCAT('legacy-personal-', id), file_name,
       COALESCE(content_type, 'application/octet-stream'), byte_size, file_content, created_at
  FROM personal_memory
 WHERE file_content IS NOT NULL AND file_name IS NOT NULL;

-- 项目工程记忆的既有事实视为已经发布；新运行从本版本起只产生候选草稿。
INSERT IGNORE INTO memory_document
    (id, scope_type, scope_id, category, state, current_published_revision_id,
     created_by, created_at, updated_at)
SELECT id, 'project', project_id, category, 'active', CONCAT('legacy-project-', id),
       COALESCE(created_by, 'ai'), created_at, updated_at
  FROM project_memory;

INSERT IGNORE INTO memory_revision
    (id, memory_id, version_no, state, title, content_md, change_summary, content_fingerprint,
     evidence_links_json, author_type, created_by, created_at, published_by, published_at)
SELECT CONCAT('legacy-project-', id), id, 1, 'published', title, content,
       '从项目工程记忆迁移', content_fingerprint, COALESCE(evidence_links_json, JSON_ARRAY()),
       CASE WHEN created_by = 'ai' THEN 'ai' ELSE 'human' END,
       COALESCE(created_by, 'ai'), created_at, COALESCE(created_by, 'ai'), updated_at
  FROM project_memory;

INSERT IGNORE INTO memory_source (id, revision_id, source_type, source_id, created_at)
SELECT CONCAT('legacy-task-', id), CONCAT('legacy-project-', id), 'task', source_task_id, created_at
  FROM project_memory WHERE source_task_id IS NOT NULL;

INSERT IGNORE INTO memory_source (id, revision_id, source_type, source_id, created_at)
SELECT CONCAT('legacy-run-', id), CONCAT('legacy-project-', id), 'run', source_run_id, created_at
  FROM project_memory WHERE source_run_id IS NOT NULL;

INSERT IGNORE INTO memory_source (id, revision_id, source_type, source_url, created_at)
SELECT CONCAT('legacy-url-', pm.id, '-', LPAD(link_item.ord, 3, '0')),
       CONCAT('legacy-project-', pm.id), 'url', link_item.url, pm.created_at
  FROM project_memory pm
  CROSS JOIN JSON_TABLE(
       COALESCE(NULLIF(pm.evidence_links_json, ''), '[]'),
       '$[*]' COLUMNS (ord FOR ORDINALITY, url VARCHAR(1024) PATH '$')
  ) AS link_item
 WHERE link_item.url IS NOT NULL AND link_item.url <> '';

-- 历史固定共享记忆知识库迁到组织域；正文保留块 JSON，避免迁移时凭空改写语义。
INSERT IGNORE INTO memory_document
    (id, scope_type, scope_id, category, state, current_published_revision_id,
     created_by, created_at, updated_at)
SELECT CONCAT('legacy-org-', d.id), 'organization', (SELECT MIN(id) FROM org), 'context', 'active',
       CONCAT('legacy-org-rev-', d.id), COALESCE(d.author_id, 'auto'),
       COALESCE(d.created_at, CURRENT_TIMESTAMP(3)), COALESCE(d.updated_at, CURRENT_TIMESTAMP(3))
  FROM doc d WHERE d.kb_id = 'kb-memory' AND d.deleted_at IS NULL;

INSERT IGNORE INTO memory_revision
    (id, memory_id, version_no, state, title, content_md, change_summary, content_fingerprint,
     evidence_links_json, author_type, created_by, created_at, published_by, published_at)
SELECT CONCAT('legacy-org-rev-', d.id), CONCAT('legacy-org-', d.id), 1, 'published', d.title,
       CAST(COALESCE(d.blocks_json, JSON_ARRAY()) AS CHAR), '从历史共享记忆知识库迁移',
       SHA2(LOWER(CAST(COALESCE(d.blocks_json, JSON_ARRAY()) AS CHAR)), 256),
       COALESCE(d.links_json, JSON_ARRAY()), CASE WHEN d.auto = 1 THEN 'ai' ELSE 'human' END,
       COALESCE(d.author_id, 'auto'), COALESCE(d.created_at, CURRENT_TIMESTAMP(3)),
       COALESCE(d.author_id, 'auto'), COALESCE(d.updated_at, CURRENT_TIMESTAMP(3))
  FROM doc d WHERE d.kb_id = 'kb-memory' AND d.deleted_at IS NULL;

-- 旧设备令牌默认只拥有其签发成员的个人域权限，组织和项目必须显式授权。
INSERT IGNORE INTO memory_access_grant
    (id, token_id, scope_type, scope_id, can_read, can_propose, can_direct_write, created_at)
SELECT CONCAT('legacy-grant-', id), id, 'personal', member_id, 1, 1, 1, created_at
  FROM memory_access_token;
