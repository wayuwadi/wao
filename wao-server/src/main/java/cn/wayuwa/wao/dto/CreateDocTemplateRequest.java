package cn.wayuwa.wao.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

/** 把一篇真实文档的当前正文另存为模板。 */
public record CreateDocTemplateRequest(
        @NotBlank String name,
        String description,
        @NotBlank String sourceDocId,
        List<DocBlockDto> blocks) {
}
