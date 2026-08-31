-- 文件、图床与托管 HTML 继续复用全局存储，只增加可空的项目归属用于分类。
-- 历史资产保留为“未分类”，不会被虚构地归入任意项目。
ALTER TABLE file_blob
    ADD COLUMN project_id VARCHAR(32) NULL AFTER created_by,
    ADD KEY idx_file_blob_project_created (project_id, created_at),
    ADD CONSTRAINT fk_file_blob_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE SET NULL;

ALTER TABLE image_blob
    ADD COLUMN project_id VARCHAR(32) NULL AFTER source,
    ADD KEY idx_image_blob_project_created (project_id, created_at),
    ADD CONSTRAINT fk_image_blob_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE SET NULL;

ALTER TABLE hosted_page
    ADD COLUMN project_id VARCHAR(32) NULL AFTER source_type,
    ADD KEY idx_hosted_page_project_updated (project_id, updated_at),
    ADD CONSTRAINT fk_hosted_page_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE SET NULL;
