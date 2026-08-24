package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 可审计、不可变的记忆修订。 */
public record MemoryRevisionDto(
        String id, int versionNo, String state, String title, String content,
        String changeSummary, String baseRevisionId, String authorType, String createdBy,
        LocalDateTime createdAt, String publishedBy, LocalDateTime publishedAt,
        LocalDateTime expiresAt, List<MemorySourceDto> sources) { }
