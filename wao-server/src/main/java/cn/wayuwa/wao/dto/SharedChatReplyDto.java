package cn.wayuwa.wao.dto;

/** 引用消息的稳定摘要；原消息删除后仍能明确引用关系。 */
public record SharedChatReplyDto(String id, String authorName, String preview) {
}
