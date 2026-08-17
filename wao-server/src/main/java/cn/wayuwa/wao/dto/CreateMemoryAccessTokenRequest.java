package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 新建一枚按设备使用的共享记忆 MCP 令牌。 */
public record CreateMemoryAccessTokenRequest(
        @NotBlank(message = "请填写设备或用途名称")
        @Size(max = 80, message = "设备或用途名称不能超过 80 个字符")
        String name) {
}
