CREATE TABLE note_mining_job (
    id VARCHAR(36) NOT NULL,
    member_id VARCHAR(32) NOT NULL,
    source_snapshot_json JSON NOT NULL,
    source_count INT NOT NULL,
    ai_run_id VARCHAR(32) NOT NULL,
    materialized_at DATETIME(6) NULL,
    error_message VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_note_mining_job_member (member_id, created_at),
    CONSTRAINT fk_note_mining_job_member FOREIGN KEY (member_id) REFERENCES member(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成员私有的小记 AI 挖掘任务';

CREATE TABLE note_mining_artifact (
    id VARCHAR(36) NOT NULL,
    job_id VARCHAR(36) NOT NULL,
    member_id VARCHAR(32) NOT NULL,
    artifact_type VARCHAR(24) NOT NULL,
    content_text TEXT NOT NULL,
    rationale_text TEXT NOT NULL,
    feedback_status VARCHAR(24) NOT NULL DEFAULT 'CANDIDATE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_note_mining_artifact_member (member_id, created_at),
    CONSTRAINT fk_note_mining_artifact_job FOREIGN KEY (job_id) REFERENCES note_mining_job(id) ON DELETE CASCADE,
    CONSTRAINT fk_note_mining_artifact_member FOREIGN KEY (member_id) REFERENCES member(id) ON DELETE CASCADE,
    CONSTRAINT chk_note_mining_artifact_type CHECK (artifact_type IN ('INSIGHT', 'REMINDER', 'ARTICLE_SEED')),
    CONSTRAINT chk_note_mining_feedback CHECK (feedback_status IN ('CANDIDATE', 'SAVED', 'NOT_ACCURATE', 'REMINDER_DRAFT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='小记挖掘产生的可解释内容候选';

CREATE TABLE note_mining_artifact_source (
    artifact_id VARCHAR(36) NOT NULL,
    doc_id VARCHAR(32) NOT NULL,
    doc_rev INT NOT NULL,
    doc_title VARCHAR(255) NOT NULL,
    excerpt VARCHAR(500) NOT NULL,
    source_ord INT NOT NULL,
    PRIMARY KEY (artifact_id, doc_id),
    CONSTRAINT fk_note_mining_source_artifact FOREIGN KEY (artifact_id) REFERENCES note_mining_artifact(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 内容产物引用的小记版本与最小摘录';
