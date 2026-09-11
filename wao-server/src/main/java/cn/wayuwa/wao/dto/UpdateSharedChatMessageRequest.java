package cn.wayuwa.wao.dto;

/** 修改本人消息；原正文用于跨窗口并发检查。 */
public record UpdateSharedChatMessageRequest(String content, String expectedContent) { }
