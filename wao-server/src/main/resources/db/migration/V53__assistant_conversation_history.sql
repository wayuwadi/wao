-- AI 助手会话与消息按成员持久化，支持跨页面、跨设备恢复历史记录。
CREATE TABLE ai_assistant_conversation (
    id                   VARCHAR(32)  NOT NULL,
    member_id            VARCHAR(32)  NOT NULL,
    title                VARCHAR(128) NOT NULL,
    last_message_preview VARCHAR(255),
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_ai_assistant_conversation_member_updated (member_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE ai_assistant_message (
    id              VARCHAR(32) NOT NULL,
    conversation_id VARCHAR(32) NOT NULL,
    role            VARCHAR(16) NOT NULL,
    content         MEDIUMTEXT  NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_ai_assistant_message_conversation_created (conversation_id, created_at),
    CONSTRAINT fk_ai_assistant_message_conversation
        FOREIGN KEY (conversation_id) REFERENCES ai_assistant_conversation(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

ALTER TABLE ai_assistant_run
    ADD COLUMN conversation_id VARCHAR(32) NULL AFTER member_id,
    ADD COLUMN user_message_id VARCHAR(32) NULL AFTER conversation_id,
    ADD INDEX idx_ai_assistant_run_conversation (conversation_id),
    ADD CONSTRAINT fk_ai_assistant_run_conversation
        FOREIGN KEY (conversation_id) REFERENCES ai_assistant_conversation(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_ai_assistant_run_user_message
        FOREIGN KEY (user_message_id) REFERENCES ai_assistant_message(id) ON DELETE SET NULL;
