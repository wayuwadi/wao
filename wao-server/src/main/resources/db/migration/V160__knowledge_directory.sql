-- 保留旧分组和文档归属，混合目录关系独立保存。
ALTER TABLE kb ADD COLUMN directory_json JSON NULL;
