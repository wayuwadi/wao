-- 成员私有饮食管理：模板、真实三餐、自我观察与备餐事项的结构化快照。
CREATE TABLE food_workspace (
    member_id    VARCHAR(32) NOT NULL,
    content_json MEDIUMTEXT  NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (member_id),
    CONSTRAINT fk_food_workspace_member
        FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
