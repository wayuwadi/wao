package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 文档选区润色请求。 */
public record PolishTextRequest(
        /** 用户在文档中明确选中的 Markdown 或纯文本。 */
        @NotBlank @Size(max = 4000) String text) {
}
