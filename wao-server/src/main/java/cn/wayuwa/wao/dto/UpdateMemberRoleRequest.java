package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;

/** 超级管理员调整成员的管理权限。 */
public record UpdateMemberRoleRequest(@NotBlank String role) {
}
