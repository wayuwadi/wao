package cn.wayuwa.wao.dto;

import java.util.List;

/** 当前成员可使用的文档模板。 */
public record DocTemplateDto(
        String id,
        String name,
        String description,
        List<DocBlockDto> blocks,
        RichDocContentDto content,
        String sourceDocId,
        String createdAt,
        String updatedAt) {
    /** 旧模板与测试兼容构造。 */
    public DocTemplateDto(String id, String name, String description, List<DocBlockDto> blocks,
            String sourceDocId, String createdAt, String updatedAt) {
        this(id, name, description, blocks, null, sourceDocId, createdAt, updatedAt);
    }
}
