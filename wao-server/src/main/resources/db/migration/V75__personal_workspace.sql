-- 成员私有工作台：分组和链接是小规模强聚合快照，所有权单独成列用于强制隔离。
CREATE TABLE personal_workspace (
    id VARCHAR(32) NOT NULL,
    owner_member_id VARCHAR(32) NOT NULL,
    project_id VARCHAR(32),
    name VARCHAR(60) NOT NULL,
    description_text VARCHAR(400) NOT NULL DEFAULT '',
    content_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_personal_workspace_owner_updated (owner_member_id, updated_at),
    KEY idx_personal_workspace_project (project_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
