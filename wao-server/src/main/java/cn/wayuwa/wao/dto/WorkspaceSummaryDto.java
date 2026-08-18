package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 工作空间切换器所需的轻量摘要。 */
public record WorkspaceSummaryDto(
        String id,
        String name,
        String description,
        String projectId,
        String projectName,
        int groupCount,
        int linkCount,
        LocalDateTime updatedAt) {
}
