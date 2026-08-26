-- 聊天页直接发起复盘：保存精确消息范围，并关联公开报告与知识库文档。
ALTER TABLE chat_review_run
    MODIFY COLUMN chat_id VARCHAR(128) NULL,
    MODIFY COLUMN kb_id VARCHAR(32) NULL,
    MODIFY COLUMN speaker_one_id VARCHAR(128) NULL,
    MODIFY COLUMN speaker_one_name VARCHAR(128) NULL,
    MODIFY COLUMN speaker_two_id VARCHAR(128) NULL,
    MODIFY COLUMN speaker_two_name VARCHAR(128) NULL,
    MODIFY COLUMN start_date DATE NULL,
    MODIFY COLUMN end_date DATE NULL,
    ADD COLUMN source_kind VARCHAR(24) NOT NULL DEFAULT 'FEISHU_ARCHIVE' AFTER id,
    ADD COLUMN range_kind VARCHAR(16) NULL AFTER focus_text,
    ADD COLUMN range_value INT NULL AFTER range_kind,
    ADD COLUMN source_message_ids_json JSON NULL AFTER range_value,
    ADD COLUMN report_url VARCHAR(1024) NULL AFTER report_json,
    ADD COLUMN report_doc_id VARCHAR(32) NULL AFTER report_url,
    ADD INDEX idx_chat_review_source_finished (source_kind, finished_at),
    ADD CONSTRAINT fk_chat_review_report_doc FOREIGN KEY (report_doc_id)
        REFERENCES doc(id) ON DELETE SET NULL;
