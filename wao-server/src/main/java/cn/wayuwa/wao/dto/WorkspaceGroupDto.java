package cn.wayuwa.wao.dto;

import java.util.List;

/** 工作台中的一个链接分组。 */
public record WorkspaceGroupDto(
        String id,
        String name,
        String icon,
        int order,
        List<WorkspaceLinkDto> links) {
}
