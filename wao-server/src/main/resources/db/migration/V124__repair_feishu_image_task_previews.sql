-- 只处理有快速收集事件一对一关联、且标题确实由旧图片 Markdown 误生成的任务。
-- 图片内容没有可靠文字来源，因此使用中性的可读标题，不猜测图片语义。
UPDATE task t
JOIN feishu_quick_collect_event e ON e.target_id = t.id
SET t.title = '图片待办',
    t.description = REPLACE(t.description, '![飞书图片](', '![任务图片](')
WHERE LEFT(t.title, 8) = '![飞书图片](';
