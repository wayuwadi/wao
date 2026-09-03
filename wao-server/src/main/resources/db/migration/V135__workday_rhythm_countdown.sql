ALTER TABLE workday_rhythm_checklist_item
    ADD COLUMN item_type VARCHAR(16) NOT NULL DEFAULT 'CHECK' AFTER title,
    ADD COLUMN duration_seconds INT NULL AFTER item_type;

ALTER TABLE workday_rhythm_checklist_occurrence
    ADD COLUMN item_type VARCHAR(16) NOT NULL DEFAULT 'CHECK' AFTER title,
    ADD COLUMN duration_seconds INT NULL AFTER item_type,
    ADD COLUMN timer_started_at DATETIME(6) NULL AFTER duration_seconds,
    ADD COLUMN timer_elapsed_seconds INT NOT NULL DEFAULT 0 AFTER timer_started_at;
