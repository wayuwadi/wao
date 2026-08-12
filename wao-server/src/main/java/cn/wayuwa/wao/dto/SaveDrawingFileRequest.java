package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 用客户端所见版本保存一份 Excalidraw 场景。 */
public record SaveDrawingFileRequest(
        @NotNull(message = "画布内容不能为空")
        @Size(max = 50_000_000, message = "画布内容超过 50MB") String sceneJson,
        @NotNull(message = "草图版本不能为空")
        @Min(value = 1, message = "草图版本不正确") Integer revision) {
}
