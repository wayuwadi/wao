-- 托管页来源是汇总文件页的稳定分类；旧人工页面保留默认值，已知 AI Run 报告按稳定 id 回填。
ALTER TABLE hosted_page
    ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL_UPLOAD' AFTER file_name,
    ADD KEY idx_hosted_page_source_updated (source_type, updated_at);

UPDATE hosted_page
SET source_type = 'AI_RUN_REPORT'
WHERE id LIKE 'run-report-%';
