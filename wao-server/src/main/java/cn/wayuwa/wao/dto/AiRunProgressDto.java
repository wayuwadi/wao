package cn.wayuwa.wao.dto;

/** 页面可展示的一条 AI 执行摘要，不包含命令和工具调用细节。 */
public record AiRunProgressDto(String id, String kind, String content, String createdAt) {
}
