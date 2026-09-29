-- Keep HTML publishing opt-in; old workspace tokens receive no additional permission.
ALTER TABLE workspace_mcp_token
    ADD COLUMN can_publish_hosted_page BOOLEAN NOT NULL DEFAULT FALSE;
