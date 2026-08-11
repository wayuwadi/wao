-- “天人一卦”按成员、日期保存服务端生成的三枚硬币卦象；同一天只保留一卦。
CREATE TABLE tianren_daily_reading (
    id              VARCHAR(32) NOT NULL,
    member_id       VARCHAR(32) NOT NULL,
    reading_date    DATE        NOT NULL,
    lines_json      VARCHAR(32) NOT NULL,
    primary_number  TINYINT     NOT NULL,
    relating_number TINYINT     NULL,
    conversation_id VARCHAR(32) NULL,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tianren_member_date (member_id, reading_date),
    KEY idx_tianren_member_date (member_id, reading_date),
    CONSTRAINT fk_tianren_conversation
        FOREIGN KEY (conversation_id) REFERENCES ai_assistant_conversation(id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
