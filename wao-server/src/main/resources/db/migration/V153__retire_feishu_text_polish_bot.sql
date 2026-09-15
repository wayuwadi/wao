-- V153：退役飞书群聊润色机器人；保留历史配置和运行记录，仅强制关闭旧开关。
UPDATE system_setting
SET cfg_value = 'false', updated_at = NOW()
WHERE cfg_key = 'feishu.text-polish.enabled';
