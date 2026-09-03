package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 修改文件在 WAO 内展示的名称；原始文件名与下载行为保持不变。 */
public record RenameFileRequest(
        @NotBlank(message = "文件展示名称不能为空")
        @Size(max = 255, message = "文件展示名称不能超过 255 个字符") String displayName) {
}
