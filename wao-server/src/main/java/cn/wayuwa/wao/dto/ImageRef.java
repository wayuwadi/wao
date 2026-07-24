package cn.wayuwa.wao.dto;

/** 任务内嵌图片引用。url 为 data: 或远程链接。 */
public record ImageRef(String name, String url) {
}
