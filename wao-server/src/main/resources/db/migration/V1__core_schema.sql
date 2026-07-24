-- WAO 核心域表：组织 / 成员 / 领域 / 项目 / 任务
-- 富嵌套结构存 detail_json（JSON），可查询字段落成真实列。

CREATE TABLE org (
    id           VARCHAR(32)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    member_count INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE member (
    id      VARCHAR(32) NOT NULL,
    name    VARCHAR(64) NOT NULL,
    initial VARCHAR(4)  NOT NULL,
    color   CHAR(7)     NOT NULL,
    role    VARCHAR(32) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE area (
    id    VARCHAR(32) NOT NULL,
    name  VARCHAR(32) NOT NULL,
    color CHAR(7)     NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE project (
    id            VARCHAR(32)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    area          VARCHAR(32)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    health        VARCHAR(16)  NOT NULL,
    progress      INT          NOT NULL DEFAULT 0,
    owner_id      VARCHAR(32)  NOT NULL,
    start_date    VARCHAR(16),
    due_date      VARCHAR(16),
    visibility    VARCHAR(64),
    updated_label VARCHAR(32),
    goal          VARCHAR(512),
    detail_json   JSON,
    PRIMARY KEY (id),
    KEY idx_project_area (area),
    KEY idx_project_status (status),
    KEY idx_project_owner (owner_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE task (
    id          VARCHAR(32)  NOT NULL,
    title       VARCHAR(256) NOT NULL,
    project_id  VARCHAR(32)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    pr          CHAR(2)      NOT NULL,
    owner_id    VARCHAR(32)  NOT NULL,
    start_date  VARCHAR(16),
    due_date    VARCHAR(16),
    waiting     VARCHAR(64),
    description TEXT,
    detail_json JSON,
    PRIMARY KEY (id),
    KEY idx_task_project (project_id),
    KEY idx_task_status (status),
    KEY idx_task_owner (owner_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
