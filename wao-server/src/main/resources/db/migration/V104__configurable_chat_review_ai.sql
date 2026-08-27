-- 聊天复盘与页面问答按次固定真实 AI 能力；自动复盘配置由数据库统一调度。
ALTER TABLE ai_assistant_run
    ADD COLUMN ai_capability_id VARCHAR(64) NULL AFTER member_id,
    ADD INDEX idx_ai_assistant_capability_status (ai_capability_id, status, created_at);

ALTER TABLE chat_review_run
    ADD COLUMN ai_capability_id VARCHAR(64) NOT NULL DEFAULT 'local-codex' AFTER focus_text,
    ADD COLUMN trigger_kind VARCHAR(16) NOT NULL DEFAULT 'MANUAL' AFTER ai_capability_id,
    ADD INDEX idx_chat_review_trigger_finished (trigger_kind, finished_at);

CREATE TABLE chat_review_auto_config (
    id                 TINYINT NOT NULL,
    enabled            BOOLEAN NOT NULL DEFAULT FALSE,
    interval_seconds   INT NOT NULL DEFAULT 21600,
    range_kind         VARCHAR(16) NOT NULL DEFAULT 'MESSAGES',
    range_value        INT NOT NULL DEFAULT 20,
    focus_text         VARCHAR(1000) NULL,
    ai_capability_id   VARCHAR(64) NOT NULL DEFAULT 'local-codex',
    next_fire_at       DATETIME(6) NULL,
    last_run_id        VARCHAR(32) NULL,
    updated_by         VARCHAR(32) NULL,
    updated_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_chat_review_auto_singleton CHECK (id = 1),
    CONSTRAINT chk_chat_review_auto_interval CHECK (interval_seconds BETWEEN 300 AND 604800),
    CONSTRAINT fk_chat_review_auto_last_run FOREIGN KEY (last_run_id)
        REFERENCES chat_review_run(id) ON DELETE SET NULL
);

INSERT INTO chat_review_auto_config
    (id, enabled, interval_seconds, range_kind, range_value, ai_capability_id, updated_at)
VALUES
    (1, FALSE, 21600, 'MESSAGES', 20, 'local-codex', CURRENT_TIMESTAMP(6));
