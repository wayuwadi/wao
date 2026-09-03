-- 知识库文档归档：一次性文档退出日常树与 AI 检索，但不进入 7 天删除流程。
ALTER TABLE doc
    ADD COLUMN archived_at DATETIME NULL COMMENT '归档时间，NULL 表示日常文档',
    ADD COLUMN archived_by VARCHAR(32) NULL COMMENT '归档操作人 member id';

CREATE INDEX idx_doc_kb_archive ON doc (kb_id, archived_at, deleted_at);
