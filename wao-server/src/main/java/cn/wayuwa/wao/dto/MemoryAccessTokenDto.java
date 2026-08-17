package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 页面可见的 MCP 令牌元数据，不包含摘要或完整令牌。 */
public record MemoryAccessTokenDto(
        String id,
        String name,
        String tokenPrefix,
        LocalDateTime createdAt,
        LocalDateTime lastUsedAt) {
}
