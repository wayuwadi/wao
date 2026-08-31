package cn.wayuwa.wao.dto;

/** 已登录成员在站内预览的 UTF-8 Markdown 文件。 */
public record FilePreviewDto(String id, String name, String contentType, String content) { }
