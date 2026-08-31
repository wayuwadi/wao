-- V124 已把明确来自飞书快速收集的旧图片标题和替代文本修正；这里补齐历史描述的文字优先顺序。
-- 只处理“开头有一张或多张任务图片，且图片后确有文字”的关联任务；纯图片和用户手写任务保持原样。
WITH parsed AS (
    SELECT t.id,
           TRIM(REGEXP_REPLACE(
               t.description,
               '^(!\\[任务图片\\]\\([^)]*\\)[[:space:]]*)+',
               '')) AS text_body,
           REGEXP_REPLACE(
               REGEXP_SUBSTR(
                   t.description,
                   '^(!\\[任务图片\\]\\([^)]*\\)[[:space:]]*)+'),
               '[[:space:]]+$',
               '') AS image_body
    FROM task t
    JOIN feishu_quick_collect_event e ON e.target_id = t.id
    WHERE LEFT(t.description, 8) = '![任务图片]('
), repair AS (
    SELECT id,
           text_body,
           image_body,
           SUBSTRING_INDEX(text_body, CHAR(10), 1) AS first_text_line
    FROM parsed
    WHERE text_body REGEXP '[^[:space:]]'
)
UPDATE task t
JOIN repair r ON r.id = t.id
SET t.title = CASE
        WHEN CHAR_LENGTH(r.first_text_line) <= 40 THEN r.first_text_line
        ELSE CONCAT(LEFT(r.first_text_line, 40), '…')
    END,
    t.description = CONCAT(r.text_body, CHAR(10), CHAR(10), r.image_body);
