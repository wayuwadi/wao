-- 成员私有形象管理：结构化工作区与按会话鉴权的实拍原件。
CREATE TABLE appearance_workspace (
    member_id    VARCHAR(32) NOT NULL,
    content_json MEDIUMTEXT  NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (member_id),
    CONSTRAINT fk_appearance_workspace_member
        FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE appearance_photo (
    id           VARCHAR(32)  NOT NULL,
    member_id    VARCHAR(32)  NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    byte_size    BIGINT       NOT NULL,
    file_content MEDIUMBLOB   NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_appearance_photo_member_created (member_id, created_at),
    CONSTRAINT fk_appearance_photo_member
        FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
