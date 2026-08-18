package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建成员私有工作空间。 */
public record CreateWorkspaceRequest(
        @NotBlank @Size(max = 60) String name,
        @Size(max = 400) String description,
        @Size(max = 32) String projectId) {
}
