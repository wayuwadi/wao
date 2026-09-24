-- 个人参考图库不复用公开图床 URL；原图只通过成员鉴权或本次 Runner 领取令牌读取。
CREATE TABLE reference_image (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    member_id VARCHAR(32) NOT NULL,
    name VARCHAR(200) NOT NULL,
    content_type VARCHAR(32) NOT NULL,
    byte_size BIGINT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    content LONGBLOB NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_reference_image_member_hash (member_id, content_hash),
    KEY idx_reference_image_member_created (member_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE ai_assistant_run
    ADD COLUMN reference_image_ids VARCHAR(512) NULL COMMENT '本次带图问答选中的私有参考图 ID 逗号分隔清单';
