-- 飞书 @润色机器人：持久化原消息、成员提示词快照、AI 运行与回群结果。
CREATE TABLE feishu_text_polish_run (
    id              VARCHAR(32)  NOT NULL,
    message_id      VARCHAR(128) NOT NULL,
    chat_id         VARCHAR(128) NOT NULL,
    sender_open_id  VARCHAR(128) NOT NULL,
    member_id       VARCHAR(32)  NULL,
    webhook_id      VARCHAR(32)  NULL,
    original_text   MEDIUMTEXT   NULL,
    prompt_snapshot TEXT         NULL,
    polished_text   MEDIUMTEXT   NULL,
    ai_run_id       VARCHAR(32)  NULL,
    record_id       VARCHAR(32)  NULL,
    status          VARCHAR(24)  NOT NULL,
    error_message   VARCHAR(500) NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_feishu_text_polish_message (message_id),
    KEY idx_feishu_text_polish_status_created (status, created_at),
    KEY idx_feishu_text_polish_member_created (member_id, created_at),
    CONSTRAINT fk_feishu_text_polish_member
        FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
