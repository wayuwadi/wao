-- 记录最近一次已通知的 Runner 在线状态，保证上线/离线只在状态变化时通知一次。
ALTER TABLE ai_runner_node
    ADD COLUMN online_state BOOLEAN NOT NULL DEFAULT TRUE AFTER last_wake_requested_at;

UPDATE ai_runner_node
SET online_state = last_seen_at IS NOT NULL
    AND last_seen_at >= DATE_SUB(NOW(), INTERVAL 2 MINUTE);
