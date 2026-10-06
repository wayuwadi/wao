-- 只记录客户端工具目录读取结果；既有访问时间不能冒充接入验证。
ALTER TABLE workspace_mcp_token
    ADD COLUMN last_verified_at DATETIME(6) NULL,
    ADD COLUMN verified_tool_count INT NULL,
    ADD COLUMN last_verification_failed_at DATETIME(6) NULL;
