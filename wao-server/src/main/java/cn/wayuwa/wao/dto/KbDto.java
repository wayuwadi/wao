package cn.wayuwa.wao.dto;

import java.util.List;

/** 知识库对外表示，字段与前端 {@code KnowledgeBase} 一致。 */
public record KbDto(
        String id,
        String name,
        String icon,
        String color,
        String project,
        Boolean auto,
        String desc,
        List<FolderDto> folders,
        String group,
        String perm) {
}
