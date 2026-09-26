ALTER TABLE ai_assistant_run
    ADD COLUMN requested_model VARCHAR(64) NULL AFTER ai_capability_id;
