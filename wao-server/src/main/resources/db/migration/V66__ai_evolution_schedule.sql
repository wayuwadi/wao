-- 自主进化频率与单次派发数量；默认值保持升级前的 12 小时、1 个任务行为。
ALTER TABLE ai_evolution_state
    ADD COLUMN interval_hours INT NOT NULL DEFAULT 12 AFTER last_low_quota_key,
    ADD COLUMN batch_size INT NOT NULL DEFAULT 1 AFTER interval_hours;
