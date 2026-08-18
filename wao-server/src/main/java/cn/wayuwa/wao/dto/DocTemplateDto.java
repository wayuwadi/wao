package cn.wayuwa.wao.dto;

import java.util.List;

/** 当前成员可使用的文档模板。 */
public record DocTemplateDto(
        String id,
        String name,
        String description,
        List<DocBlockDto> blocks,
        String sourceDocId,
        String createdAt,
        String updatedAt) {
}
