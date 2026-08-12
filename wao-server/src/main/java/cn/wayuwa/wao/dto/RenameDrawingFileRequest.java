package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 重命名草图文件。 */
public record RenameDrawingFileRequest(
        @NotBlank(message = "草图名称不能为空")
        @Size(max = 128, message = "草图名称不能超过 128 个字符") String title) {
}
