-- 沿用小记的持久私有标记；每篇正文仅拆分一次，避免逐素材反复扫描全部正文。
-- JSON_QUOTE 保留正文中的引号、反斜杠和换行；第一段是标记之前的正文，不是素材 ID。
INSERT IGNORE INTO private_note_asset
 SELECT 'images',id FROM image_blob WHERE source LIKE '文档%';
INSERT IGNORE INTO private_note_asset
 SELECT 'images',i.id FROM doc d
 JOIN JSON_TABLE(
   CONCAT('[',REPLACE(JSON_QUOTE(CONCAT_WS('',d.content_markdown,d.content_json,d.blocks_json)),
     '/api/images/','","'),']'),
   '$[*]' COLUMNS(seq FOR ORDINALITY, remainder LONGTEXT PATH '$')
 ) AS refs ON refs.seq>1
 JOIN image_blob i ON i.id=SUBSTRING_INDEX(refs.remainder,'/',1);
INSERT IGNORE INTO private_note_asset
 SELECT 'files',f.id FROM doc d
 JOIN JSON_TABLE(
   CONCAT('[',REPLACE(JSON_QUOTE(CONCAT_WS('',d.content_markdown,d.content_json,d.blocks_json)),
     '/api/files/','","'),']'),
   '$[*]' COLUMNS(seq FOR ORDINALITY, remainder LONGTEXT PATH '$')
 ) AS refs ON refs.seq>1
 JOIN file_blob f ON f.id=SUBSTRING_INDEX(refs.remainder,'/',1);
