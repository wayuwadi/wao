-- 沿用小记的持久私有标记；删除或移除引用不重新公开历史素材。
INSERT IGNORE INTO private_note_asset
 SELECT 'images',i.id FROM image_blob i WHERE i.source LIKE '文档%'
 OR EXISTS(SELECT 1 FROM doc d WHERE
 CONCAT_WS('',d.content_markdown,d.content_json,d.blocks_json) LIKE CONCAT('%/api/images/',i.id,'/%'));
INSERT IGNORE INTO private_note_asset
 SELECT 'files',f.id FROM file_blob f WHERE EXISTS(SELECT 1 FROM doc d WHERE
 CONCAT_WS('',d.content_markdown,d.content_json,d.blocks_json) LIKE CONCAT('%/api/files/',f.id,'/%'));
