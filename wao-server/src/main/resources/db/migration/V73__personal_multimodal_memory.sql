-- 个人生活记忆：正文与多模态原件都按成员隔离，列表查询不读取 file_content。
CREATE TABLE personal_memory (
    id VARCHAR(32) NOT NULL,
    member_id VARCHAR(32) NOT NULL,
    title VARCHAR(120) NOT NULL,
    content TEXT,
    category VARCHAR(32) NOT NULL,
    capture_mode VARCHAR(16) NOT NULL,
    media_type VARCHAR(16) NOT NULL,
    file_name VARCHAR(255),
    content_type VARCHAR(160),
    byte_size BIGINT,
    file_content LONGBLOB,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_personal_memory_member_updated (member_id, updated_at),
    KEY idx_personal_memory_member_category (member_id, category)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
