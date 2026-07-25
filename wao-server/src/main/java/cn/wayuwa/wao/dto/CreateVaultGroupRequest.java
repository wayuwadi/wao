package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;

/** 新建密钥库分组请求。 */
public record CreateVaultGroupRequest(@NotBlank String name) {
}
