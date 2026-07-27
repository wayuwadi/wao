-- 网站公开信息与备案号：用 wayuwa.cn 已备案的主体信息作为 WAO 初始值，
-- 后续可在「设置 → 网站信息 · 备案」直接维护，无需改代码或重新部署。
INSERT INTO system_setting (cfg_key, cfg_value, updated_at) VALUES
    ('site.name', 'WAO Life OS', NOW()),
    ('site.description', '个人事务与 AI 协作系统', NOW()),
    ('site.icp-number', '豫ICP备2024105476号-1', NOW()),
    ('site.icp-url', 'https://beian.miit.gov.cn', NOW())
ON DUPLICATE KEY UPDATE cfg_key = VALUES(cfg_key);
