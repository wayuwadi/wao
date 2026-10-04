ALTER TABLE workspace_mcp_token
    ADD COLUMN permission_policy JSON NULL COMMENT '逐项授权快照；NULL 保留旧版整体授权',
    ADD COLUMN client_type VARCHAR(16) NULL COMMENT '签发时标记 CLI 或 MCP；历史连接不猜测';
