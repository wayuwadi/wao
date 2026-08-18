package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 一个成员私有工作空间的完整可编辑快照。 */
public record WorkspaceDetailDto(
        String id,
        String name,
        String description,
        String projectId,
        String projectName,
        int version,
        List<WorkspaceGroupDto> groups,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
