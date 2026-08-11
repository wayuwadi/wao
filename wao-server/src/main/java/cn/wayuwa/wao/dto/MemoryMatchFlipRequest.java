package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 翻开一个卡片位置，牌面、配对和计步全部由服务端判断。 */
public record MemoryMatchFlipRequest(
        @Min(value = 0, message = "卡片位置不能小于 0")
        @Max(value = 15, message = "卡片位置不能大于 15")
        int index,
        @Min(value = 0, message = "存档版本不能小于 0")
        int revision) {
}
