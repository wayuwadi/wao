package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 统一记忆中心的一篇文档及当前展示版本。 */
public record MemoryDocumentDto(
        String id, String scopeType, String scopeId, String category, String state,
        String currentPublishedRevisionId, MemoryRevisionDto revision,
        String createdBy, LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime archivedAt) { }
