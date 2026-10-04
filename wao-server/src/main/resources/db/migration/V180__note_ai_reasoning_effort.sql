ALTER TABLE ai_assistant_run
    ADD COLUMN reasoning_effort VARCHAR(16) NULL AFTER requested_model;
