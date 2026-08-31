package cn.wayuwa.wao.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创作者用于初始化账号定位的实际需求。 */
public record InitializeProfileRequest(
        @NotBlank(message = "请先描述账号和创作需求") @Size(max = 2000) String needs) {
}
