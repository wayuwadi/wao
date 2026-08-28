-- 成员独立润色工作台：保留当次提示词快照、润色前原文和真实 AI 返回结果。
CREATE TABLE text_polish_record (
    id              VARCHAR(32) NOT NULL,
    member_id       VARCHAR(32) NOT NULL,
    prompt_snapshot TEXT        NOT NULL,
    original_text   MEDIUMTEXT  NOT NULL,
    polished_text   MEDIUMTEXT  NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_text_polish_member_created (member_id, created_at),
    CONSTRAINT fk_text_polish_record_member
        FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
