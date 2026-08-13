-- 自主目标与任务描述允许长 Markdown；图片正常存图床，历史 data URL 仍需可完整保存。
ALTER TABLE task MODIFY COLUMN description MEDIUMTEXT NULL;
