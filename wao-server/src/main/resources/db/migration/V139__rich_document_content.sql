-- 文档高级编辑器：Tiptap JSON 保存完整编辑结构，Markdown 保留分享与 AI 可读投影。
-- 老 blocks_json 继续作为滚动发布兼容层，不批量改写历史正文。
ALTER TABLE doc
    ADD COLUMN content_json JSON NULL COMMENT '版本化 Tiptap 文档 JSON；NULL 表示沿用旧正文块',
    ADD COLUMN content_markdown MEDIUMTEXT NULL COMMENT '分享、检索与旧客户端降级使用的 Markdown 投影',
    ADD COLUMN content_version INT NULL COMMENT '高级正文结构版本，当前为 1';

ALTER TABLE doc_template
    ADD COLUMN content_json JSON NULL COMMENT '模板的版本化 Tiptap 文档 JSON',
    ADD COLUMN content_markdown MEDIUMTEXT NULL COMMENT '模板的 Markdown 投影',
    ADD COLUMN content_version INT NULL COMMENT '高级正文结构版本，当前为 1';
