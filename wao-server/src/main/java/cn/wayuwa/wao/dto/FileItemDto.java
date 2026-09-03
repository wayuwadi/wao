package cn.wayuwa.wao.dto;

/** 文件列表项；displayName 是站内展示名，name 始终保留原始文件名供下载和类型识别。 */
public record FileItemDto(String id, String name, String displayName, String contentType, String size, String icon,
        String time, String by, String link, String createdAt, String projectId) {
}
