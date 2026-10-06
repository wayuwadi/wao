package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 工作区 MCP 令牌的非敏感元数据。 */
public record WorkspaceMcpTokenDto(String id, String name, String tokenPrefix, boolean canWrite,
        boolean canPublishHostedPage, LocalDateTime createdAt, LocalDateTime lastUsedAt,
        String permissionMode, List<String> permissions, List<String> projectIds,
        List<String> knowledgeBaseIds, String clientType, LocalDateTime lastVerifiedAt,
        Integer verifiedToolCount, LocalDateTime lastVerificationFailedAt) {
    public WorkspaceMcpTokenDto(String id, String name, String tokenPrefix, boolean canWrite,
            boolean canPublishHostedPage, LocalDateTime createdAt, LocalDateTime lastUsedAt) {
        this(id, name, tokenPrefix, canWrite, canPublishHostedPage, createdAt, lastUsedAt,
                "legacy", null, null, null, null, null, null, null);
    }
}
