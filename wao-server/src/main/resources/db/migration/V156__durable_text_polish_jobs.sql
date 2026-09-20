CREATE TABLE text_polish_job (
    id VARCHAR(36) NOT NULL COMMENT '客户端幂等请求标识',
    member_id VARCHAR(32) NOT NULL COMMENT '所属成员',
    kind VARCHAR(16) NOT NULL COMMENT 'DOCUMENT 或 WORKSPACE',
    original_text TEXT NOT NULL COMMENT '冻结原文',
    prompt_snapshot TEXT NOT NULL COMMENT '冻结润色方法',
    ai_run_id VARCHAR(32) NOT NULL COMMENT '既有只读 AI 队列运行',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_text_polish_job_member (member_id, created_at),
    CONSTRAINT fk_text_polish_job_member FOREIGN KEY (member_id) REFERENCES member(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可刷新恢复的成员私有润色任务';
