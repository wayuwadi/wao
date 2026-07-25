package cn.wayuwa.wao.dto;

/** 文件列表项；link 是可直接下载的站内地址。 */
public record FileItemDto(String id, String name, String size, String icon, String time, String by, String link) {
}
