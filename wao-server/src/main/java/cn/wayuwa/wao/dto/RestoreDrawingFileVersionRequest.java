package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 恢复历史场景时携带客户端当前所见版本，避免覆盖其它页面的新修改。 */
public record RestoreDrawingFileVersionRequest(
        @NotNull(message = "草图版本不能为空")
        @Min(value = 1, message = "草图版本不正确") Integer revision) {
}
