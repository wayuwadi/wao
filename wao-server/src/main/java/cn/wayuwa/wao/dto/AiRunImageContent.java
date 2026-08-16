package cn.wayuwa.wao.dto;

/** 旧 AI 运行中内联图片的安全预览正文。 */
public record AiRunImageContent(String contentType, byte[] content) {
}
