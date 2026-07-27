-- 已完成任务记录完成时间：列表和看板按最新完成倒序展示才有依据。
-- 存量已完成任务没有历史完成时间可考，留空，前端回落到任务 id 里的创建时间戳排序。
ALTER TABLE task ADD COLUMN completed_at DATETIME NULL;

-- 飞书群机器人切换到新 webhook：库里种下新地址并启用开关后，
-- SettingService 读库优先于环境变量兜底，旧地址随即停用。
-- 该地址由需求方在任务里公开下发，且 decode() 对无 enc: 前缀的值原样返回，可明文写入。
DELETE FROM system_setting WHERE cfg_key IN ('feishu.webhook.url', 'feishu.webhook.enabled');
INSERT INTO system_setting (cfg_key, cfg_value, updated_at) VALUES
    ('feishu.webhook.url', '', NOW()),
    ('feishu.webhook.enabled', 'true', NOW());
