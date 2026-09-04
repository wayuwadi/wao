ALTER TABLE agent_publish_task
    ADD COLUMN generation_executor VARCHAR(24) NULL AFTER source_context_json,
    ADD COLUMN generation_system_prompt LONGTEXT NULL AFTER generation_executor,
    ADD COLUMN generation_user_prompt LONGTEXT NULL AFTER generation_system_prompt,
    ADD COLUMN generation_claim_token VARCHAR(64) NULL AFTER generation_user_prompt,
    ADD COLUMN generation_lease_until DATETIME(3) NULL AFTER generation_claim_token,
    ADD INDEX idx_agent_publish_generation
        (status, generation_executor, generation_lease_until, updated_at);
