-- 组织共享轻量聊天室：正文先可靠落库，飞书出站状态独立记录并可重试。
CREATE TABLE shared_chat_message (
    id             VARCHAR(32)   NOT NULL,
    member_id      VARCHAR(32)   NOT NULL,
    client_token   VARCHAR(64)   NOT NULL,
    content        VARCHAR(2000) NOT NULL DEFAULT '',
    image_id       VARCHAR(32)   NULL,
    image_name     VARCHAR(255)  NULL,
    feishu_status  VARCHAR(16)   NOT NULL,
    feishu_error   VARCHAR(255)  NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shared_chat_member_token (member_id, client_token),
    KEY idx_shared_chat_created (created_at, id),
    CONSTRAINT fk_shared_chat_member FOREIGN KEY (member_id) REFERENCES member(id),
    CONSTRAINT fk_shared_chat_image FOREIGN KEY (image_id) REFERENCES image_blob(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
