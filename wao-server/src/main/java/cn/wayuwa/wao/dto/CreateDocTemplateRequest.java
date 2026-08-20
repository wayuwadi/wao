package cn.wayuwa.wao.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 把一篇真实文档的当前正文另存为模板。 */
public record CreateDocTemplateRequest(
        @NotBlank(message = "模板名称不能为空")
        @Size(max = 128, message = "模板名称不能超过 128 个字符") String name,
        @Size(max = 512, message = "模板说明不能超过 512 个字符") String description,
        @NotBlank String sourceDocId,
        @Size(max = 5_000, message = "模板正文不能超过 5000 个块") List<DocBlockDto> blocks) {
}
