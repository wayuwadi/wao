-- 爬虫能力是可复用的源码资产，不等同于自动化任务，也不会被通用调度器直接执行。
CREATE TABLE crawler_capability (
    id            VARCHAR(32)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    description   VARCHAR(512) NOT NULL DEFAULT '',
    language      VARCHAR(16)  NOT NULL,
    source_code   MEDIUMTEXT   NOT NULL,
    enabled       TINYINT(1)   NOT NULL DEFAULT 0,
    revision      INT          NOT NULL DEFAULT 1,
    created_by    VARCHAR(32)  NOT NULL,
    updated_by    VARCHAR(32)  NOT NULL,
    created_at    DATETIME(3)  NOT NULL,
    updated_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_crawler_capability_updated (updated_at),
    CONSTRAINT chk_crawler_capability_language
        CHECK (language IN ('PYTHON', 'JAVASCRIPT'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
