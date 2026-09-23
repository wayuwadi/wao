-- 工作区 MCP 使用独立令牌，历史记忆令牌不会自动获得任务、项目或知识库权限。
CREATE TABLE workspace_mcp_token (
    id           VARCHAR(32) NOT NULL,
    member_id    VARCHAR(32) NOT NULL,
    name         VARCHAR(80) NOT NULL,
    token_prefix VARCHAR(20) NOT NULL,
    token_hash   CHAR(64) NOT NULL,
    can_write    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   DATETIME NOT NULL,
    last_used_at DATETIME NULL,
    revoked_at   DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_workspace_mcp_token_hash (token_hash),
    KEY idx_workspace_mcp_token_member (member_id, revoked_at, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
