-- 仅收起任务点名清理的旧成员目录，保留账号、历史任务与聊天作者引用。
ALTER TABLE member ADD COLUMN directory_visible TINYINT(1) NOT NULL DEFAULT 1;
UPDATE member SET directory_visible = 0
WHERE name IN ('娃妹', 'ChatGPT对话功能验收', 'WAO-HUB 验收专用 armttk');
