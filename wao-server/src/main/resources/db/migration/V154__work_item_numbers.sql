-- Durable display numbers: sequence is 1-based internally, API exposes sequence - 1.
-- No foreign key cascade: deleted identities must never recycle a display number.
CREATE TABLE work_item_number (
  sequence_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  item_key VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  UNIQUE KEY uk_work_item_number_key (item_key)
);
INSERT INTO work_item_number (item_key)
SELECT item_key FROM (
  SELECT CONCAT(id, '/') AS item_key, id AS task_id, 0 AS position FROM task
  UNION ALL
  SELECT CONCAT(t.id, '/', COALESCE(NULLIF(j.sub_id, ''), CONCAT(t.id, '#', j.ord - 1))),
         t.id, j.ord
  FROM task t JOIN JSON_TABLE(COALESCE(t.detail_json, '{}'), '$.subs[*]'
    COLUMNS(ord FOR ORDINALITY, sub_id VARCHAR(255) PATH '$.id' NULL ON EMPTY)) j
) items GROUP BY item_key ORDER BY MIN(task_id), MIN(position);
