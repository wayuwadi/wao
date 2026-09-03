ALTER TABLE attendance_connector
    ADD COLUMN history_synced_from DATE NULL AFTER last_error,
    ADD COLUMN history_backfill_claim_token VARCHAR(36) NULL AFTER history_synced_from,
    ADD COLUMN history_backfill_claimed_at DATETIME(6) NULL AFTER history_backfill_claim_token;

CREATE INDEX idx_attendance_history_claim
    ON attendance_connector (enabled, history_backfill_claimed_at);
