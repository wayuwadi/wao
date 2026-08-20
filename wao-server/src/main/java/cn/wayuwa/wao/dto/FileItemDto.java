package cn.wayuwa.wao.dto;

/** 文件列表项；link 是可直接下载的站内地址，contentType 用于识别可播放音频等文件种类。 */
public record FileItemDto(String id, String name, String contentType, String size, String icon, String time, String by,
        String link, String createdAt) {
}
