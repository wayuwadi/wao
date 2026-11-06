-- Personal task templates are independent from documents and task records.
CREATE TABLE task_template (
    id VARCHAR(32) NOT NULL,
    member_id VARCHAR(64) NOT NULL,
    content_json LONGTEXT NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_task_template_member (member_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- A receipt makes creation retries read the existing task rather than overwriting it.
CREATE TABLE task_template_creation (
    task_id VARCHAR(32) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    member_id VARCHAR(64) NOT NULL,
    template_id VARCHAR(32) NOT NULL,
    project_id VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
