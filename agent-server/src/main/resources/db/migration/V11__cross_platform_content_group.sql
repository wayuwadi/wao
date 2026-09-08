ALTER TABLE agent_publish_task
    ADD COLUMN content_group_id VARCHAR(36) NULL AFTER requested_by_account_id,
    ADD INDEX idx_agent_publish_content_group (workspace_id, content_group_id, created_at);
