package cn.wayuwa.wao.dto;

import java.util.List;

/** 项目工程记忆及其来源证据。 */
public record ProjectMemoryDto(
        String id,
        String projectId,
        String category,
        String title,
        String content,
        String sourceTaskId,
        String sourceRunId,
        List<String> evidenceLinks,
        String createdBy,
        String createdAt,
        String updatedAt) {
}
