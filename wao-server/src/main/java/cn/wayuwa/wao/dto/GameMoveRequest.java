package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 数字熔炉的一步移动；revision 防止多个页面互相覆盖。 */
public record GameMoveRequest(
        @NotBlank(message = "移动方向不能为空")
        @Pattern(regexp = "UP|DOWN|LEFT|RIGHT", message = "移动方向不正确")
        String direction,
        @NotNull(message = "存档版本不能为空")
        @Min(value = 0, message = "存档版本不能小于 0")
        Integer revision) {
}
