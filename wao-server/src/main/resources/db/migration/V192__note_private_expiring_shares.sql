CREATE TABLE private_note_asset (
 asset_type VARCHAR(8) NOT NULL,
 asset_id VARCHAR(64) NOT NULL,
 PRIMARY KEY(asset_type,asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 私有标记独立于原文外键：删掉正文引用或原文也不能恢复匿名读取。
INSERT IGNORE INTO private_note_asset SELECT asset_type,asset_id FROM note_source_asset_ref;
INSERT IGNORE INTO private_note_asset
 SELECT 'images',i.id FROM image_blob i WHERE i.source LIKE '小记%'
 OR EXISTS(SELECT 1 FROM note_ai_workflow w WHERE i.source=CONCAT('文章总览图 · ',w.image_run_id))
 OR EXISTS(SELECT 1 FROM note_ai_transformation t WHERE i.source=CONCAT('文章总览图 · ',t.task_id))
 OR EXISTS(SELECT 1 FROM doc d WHERE d.kb_id='kb-notes' AND
 CONCAT_WS('',d.content_markdown,d.content_json,d.blocks_json) LIKE CONCAT('%/api/images/',i.id,'/%'));
INSERT IGNORE INTO private_note_asset
 SELECT 'files',f.id FROM file_blob f WHERE EXISTS(SELECT 1 FROM doc d WHERE d.kb_id='kb-notes' AND
 CONCAT_WS('',d.content_markdown,d.content_json,d.blocks_json) LIKE CONCAT('%/api/files/',f.id,'/%'));

CREATE TABLE note_public_share (
 id CHAR(32) PRIMARY KEY,
 doc_id VARCHAR(32) NOT NULL,
 owner_id VARCHAR(32) NOT NULL,
 token_hash CHAR(64) NOT NULL,
 snapshot_json JSON NOT NULL,
 created_at DATETIME(6) NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 revoked_at DATETIME(6) NULL,
 UNIQUE KEY uk_note_public_share_token(token_hash),
 KEY idx_note_public_share_owner(doc_id,owner_id,expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
