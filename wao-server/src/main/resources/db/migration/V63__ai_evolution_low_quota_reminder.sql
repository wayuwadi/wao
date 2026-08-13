-- 同一账号的同一个周额度重置周期只发一次低额度提醒。
ALTER TABLE ai_evolution_state
    ADD COLUMN last_low_quota_key TEXT AFTER last_suggestion_id;
