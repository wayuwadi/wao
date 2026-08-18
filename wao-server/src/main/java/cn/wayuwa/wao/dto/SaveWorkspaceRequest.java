package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 原子保存一个工作空间的完整快照。 */
public record SaveWorkspaceRequest(
        @NotBlank @Size(max = 60) String name,
        @Size(max = 400) String description,
        @Size(max = 32) String projectId,
        List<WorkspaceGroupDto> groups) {
}
