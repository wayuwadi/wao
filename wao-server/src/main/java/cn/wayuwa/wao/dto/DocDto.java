package cn.wayuwa.wao.dto;

import java.util.List;

/** 文档对外表示，字段与前端 {@code Doc} 一致。 */
public record DocDto(
        String id,
        String title,
        String author,
        String created,
        String updated,
        Boolean auto,
        List<DocLinkDto> links,
        List<DocBlockDto> blocks,
        List<BacklinkDto> backlinks) {
}
