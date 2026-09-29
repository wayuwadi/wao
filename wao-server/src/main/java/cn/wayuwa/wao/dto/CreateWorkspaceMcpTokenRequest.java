package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 工作区 MCP 设备令牌签发请求。 */
public record CreateWorkspaceMcpTokenRequest(@NotBlank @Size(max = 80) String name,
        @NotNull Boolean canWrite, Boolean canPublishHostedPage) { }
