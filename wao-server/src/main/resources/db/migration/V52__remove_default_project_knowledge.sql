-- 项目不再默认创建知识库。仅清理随原型种子发布的两套项目知识库，
-- 不碰用户后来主动创建的关联库；沿用 ADR-018 的 7 天软删除语义。
SET @wao_deleted_at = CURRENT_TIMESTAMP;

UPDATE kb
SET deleted_at = @wao_deleted_at,
    deleted_by = 'system'
WHERE id IN ('k1', 'k2')
  AND project_id IS NOT NULL
  AND deleted_at IS NULL;

UPDATE kb_folder
SET deleted_at = @wao_deleted_at,
    cascade_del = 1
WHERE kb_id IN ('k1', 'k2')
  AND deleted_at IS NULL;

UPDATE doc
SET deleted_at = @wao_deleted_at,
    deleted_by = 'system',
    cascade_del = 1
WHERE kb_id IN ('k1', 'k2')
  AND deleted_at IS NULL;
