package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 重开或撤销时携带的当前存档版本。 */
public record GameRevisionRequest(
        @NotNull(message = "存档版本不能为空")
        @Min(value = 0, message = "存档版本不能小于 0")
        Integer revision) {
}
