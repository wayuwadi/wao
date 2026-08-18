-- 文档模板：按创建成员隔离，正文继续沿用文档块 JSON，避免把模板伪装成普通文档。
CREATE TABLE doc_template (
    id            VARCHAR(32)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    description   VARCHAR(512),
    blocks_json   JSON,
    source_doc_id VARCHAR(32),
    created_by    VARCHAR(32)  NOT NULL,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    ord           INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_doc_template_owner (created_by, ord),
    KEY idx_doc_template_source (source_doc_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
