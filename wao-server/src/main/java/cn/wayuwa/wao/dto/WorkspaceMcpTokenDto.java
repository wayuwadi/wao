package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 工作区 MCP 令牌的非敏感元数据。 */
public record WorkspaceMcpTokenDto(String id, String name, String tokenPrefix, boolean canWrite,
        LocalDateTime createdAt, LocalDateTime lastUsedAt) { }
