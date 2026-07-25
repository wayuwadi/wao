-- 知识库域关系建模：分组 / 知识库 / 文件夹 / 文档，替换 V3 中通用 payload 的 kb / doc 表。
-- 文档正文（blocks）与出链（links）仍为富嵌套结构，落 JSON 列；反向链接改为按 links 动态查询，不再落库。

DROP TABLE IF EXISTS doc;
DROP TABLE IF EXISTS kb;

CREATE TABLE kb_group (
    id   VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    ord  INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE kb (
    id          VARCHAR(32)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    icon        VARCHAR(64)  NOT NULL DEFAULT 'ri-book-2-line',
    color       CHAR(7)      NOT NULL DEFAULT '#4358D6',
    project_id  VARCHAR(32),
    auto        TINYINT(1)   NOT NULL DEFAULT 0,
    description VARCHAR(512),
    group_id    VARCHAR(32),
    perm        VARCHAR(16)  NOT NULL DEFAULT '仅自己',
    ord         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_kb_project (project_id),
    KEY idx_kb_group (group_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE kb_folder (
    id    VARCHAR(32)  NOT NULL,
    kb_id VARCHAR(32)  NOT NULL,
    name  VARCHAR(128) NOT NULL,
    ord   INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_folder_kb (kb_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE doc (
    id            VARCHAR(32)  NOT NULL,
    kb_id         VARCHAR(32)  NOT NULL,
    folder_id     VARCHAR(32)  NOT NULL,
    title         VARCHAR(256) NOT NULL DEFAULT '',
    author_id     VARCHAR(32)  NOT NULL,
    auto          TINYINT(1)   NOT NULL DEFAULT 0,
    created_label VARCHAR(32),
    updated_label VARCHAR(32),
    blocks_json   JSON,
    links_json    JSON,
    ord           INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_doc_kb (kb_id),
    KEY idx_doc_folder (folder_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
