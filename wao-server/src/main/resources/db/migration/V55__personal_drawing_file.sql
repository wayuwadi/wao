-- 个人草图文件：所有读写都按当前会话成员隔离，场景使用 Excalidraw 开放 JSON 格式。
CREATE TABLE drawing_file (
    id              VARCHAR(32)  NOT NULL,
    owner_member_id VARCHAR(32)  NOT NULL,
    title           VARCHAR(128) NOT NULL,
    scene_json      LONGTEXT     NOT NULL,
    revision        INT          NOT NULL DEFAULT 1,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_drawing_owner_updated (owner_member_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
