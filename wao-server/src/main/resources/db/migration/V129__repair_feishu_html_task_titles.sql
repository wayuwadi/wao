-- 只修复有快速收集事件一对一关联、且标题确实混入 HTML 的历史任务。
-- 可见文字从原始描述重新提取，避免旧标题已经截断后丢失第一段；描述本身继续保留原有富文本格式。
WITH visible_text AS (
    SELECT t.id,
           REGEXP_REPLACE(
               REGEXP_REPLACE(
                   t.description,
                   '<[[:space:]]*/?[[:space:]]*(p|div|h[1-6]|li|ul|ol|blockquote)[^>]*>|<[[:space:]]*br[[:space:]]*/?[[:space:]]*>',
                   CONVERT(CHAR(10) USING utf8mb4), 1, 0, 'i'),
               '<[[:space:]]*/?[[:space:]]*[[:alpha:]][^>]*>',
               '', 1, 0, 'i') AS body
    FROM task t
    JOIN feishu_quick_collect_event e ON e.target_id = t.id
    WHERE t.title REGEXP '<[[:space:]]*/?[[:space:]]*[[:alpha:]][^>]*>'
      AND t.description IS NOT NULL
), first_paragraph AS (
    SELECT id,
           TRIM(SUBSTRING_INDEX(
               REGEXP_REPLACE(body, '^[[:space:]]+', ''),
               CONVERT(CHAR(10) USING utf8mb4), 1)) AS line
    FROM visible_text
    WHERE body REGEXP '[^[:space:]]'
), decoded AS (
    SELECT id,
           REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
               line,
               '&nbsp;', ' '),
               '&amp;', '&'),
               '&lt;', '<'),
               '&gt;', '>'),
               '&quot;', '"'),
               '&#39;', '''') AS line
    FROM first_paragraph
    WHERE line REGEXP '[^[:space:]]'
)
UPDATE task t
JOIN decoded d ON d.id = t.id
SET t.title = CASE
        WHEN CHAR_LENGTH(d.line) <= 40 THEN d.line
        ELSE CONCAT(LEFT(d.line, 40), '…')
    END;
