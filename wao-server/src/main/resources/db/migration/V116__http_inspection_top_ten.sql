-- 只升级仍沿用旧默认值的巡检范围；管理员已经自定义的数量继续保留。
INSERT INTO system_setting (cfg_key, cfg_value, updated_at)
VALUES ('http.inspection.top-n', '10', NOW())
ON DUPLICATE KEY UPDATE
    updated_at = IF(cfg_value = '5', VALUES(updated_at), updated_at),
    cfg_value = IF(cfg_value = '5', VALUES(cfg_value), cfg_value);

INSERT INTO system_setting (cfg_key, cfg_value, updated_at)
VALUES ('http.inspection.max-endpoints', '10', NOW())
ON DUPLICATE KEY UPDATE
    cfg_key = VALUES(cfg_key);

UPDATE system_setting AS max_setting
JOIN system_setting AS top_setting ON top_setting.cfg_key = 'http.inspection.top-n'
SET max_setting.cfg_value = '10', max_setting.updated_at = NOW()
WHERE max_setting.cfg_key = 'http.inspection.max-endpoints'
  AND max_setting.cfg_value = '2'
  AND top_setting.cfg_value = '10';
