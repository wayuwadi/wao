package cn.wayuwa.wao.dto;

/** 发送共享聊天消息；作者只能由服务端会话确定。 */
public record CreateSharedChatMessageRequest(String clientToken, String content, String imageId) {
}
