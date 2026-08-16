CREATE TABLE life_review (
    id                           VARCHAR(32)  NOT NULL,
    member_id                    VARCHAR(32)  NOT NULL,
    period_type                  VARCHAR(16)  NOT NULL,
    period_key                   VARCHAR(24)  NOT NULL,
    period_start                 DATE         NOT NULL,
    period_end                   DATE         NOT NULL,
    task_count                   INT          NOT NULL DEFAULT 0,
    assistant_conversation_count INT          NOT NULL DEFAULT 0,
    chat_day_count               INT          NOT NULL DEFAULT 0,
    sources_json                 JSON         NOT NULL,
    report_json                  JSON         NOT NULL,
    generated_at                 DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_life_review_member_period (member_id, period_type, period_key),
    KEY idx_life_review_member_generated (member_id, generated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
