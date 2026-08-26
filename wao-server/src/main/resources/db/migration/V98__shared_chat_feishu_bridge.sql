-- 共享聊天与飞书自建应用双向桥接：外部消息身份、回复关系、真实已读与表情回执。
ALTER TABLE shared_chat_message
    MODIFY COLUMN member_id VARCHAR(32) NULL,
    MODIFY COLUMN client_token VARCHAR(64) NULL,
    MODIFY COLUMN content MEDIUMTEXT NOT NULL,
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'WAO' AFTER client_token,
    ADD COLUMN external_sender_id VARCHAR(191) NULL AFTER source,
    ADD COLUMN external_sender_name VARCHAR(255) NULL AFTER external_sender_id,
    ADD COLUMN feishu_chat_id VARCHAR(128) NULL AFTER image_name,
    ADD COLUMN feishu_message_id VARCHAR(191) NULL AFTER feishu_chat_id,
    ADD COLUMN reply_to_id VARCHAR(32) NULL AFTER feishu_message_id,
    ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE AFTER reply_to_id,
    ADD UNIQUE KEY uk_shared_chat_feishu_message (feishu_message_id),
    ADD KEY idx_shared_chat_reply (reply_to_id),
    ADD CONSTRAINT fk_shared_chat_reply FOREIGN KEY (reply_to_id)
        REFERENCES shared_chat_message(id) ON DELETE SET NULL;

CREATE TABLE shared_chat_message_read (
    id          VARCHAR(32)  NOT NULL,
    message_id  VARCHAR(32)  NOT NULL,
    reader_key  VARCHAR(223) NOT NULL,
    member_id   VARCHAR(32)  NULL,
    source      VARCHAR(16)  NOT NULL,
    read_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shared_chat_read (message_id, reader_key, source),
    KEY idx_shared_chat_read_member (member_id, read_at),
    CONSTRAINT fk_shared_chat_read_message FOREIGN KEY (message_id)
        REFERENCES shared_chat_message(id) ON DELETE CASCADE,
    CONSTRAINT fk_shared_chat_read_member FOREIGN KEY (member_id)
        REFERENCES member(id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE shared_chat_reaction (
    id                   VARCHAR(32)  NOT NULL,
    message_id           VARCHAR(32)  NOT NULL,
    reactor_key          VARCHAR(223) NOT NULL,
    member_id            VARCHAR(32)  NULL,
    reactor_name         VARCHAR(255) NOT NULL,
    emoji_type           VARCHAR(48)  NOT NULL,
    source               VARCHAR(16)  NOT NULL,
    feishu_reaction_id   VARCHAR(191) NULL,
    created_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shared_chat_reaction (message_id, reactor_key, emoji_type),
    KEY idx_shared_chat_reaction_message (message_id, created_at),
    CONSTRAINT fk_shared_chat_reaction_message FOREIGN KEY (message_id)
        REFERENCES shared_chat_message(id) ON DELETE CASCADE,
    CONSTRAINT fk_shared_chat_reaction_member FOREIGN KEY (member_id)
        REFERENCES member(id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
