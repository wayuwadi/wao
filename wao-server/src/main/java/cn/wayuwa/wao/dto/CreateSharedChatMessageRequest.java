package cn.wayuwa.wao.dto;

/** 发送共享聊天消息；作者只能由服务端会话确定。 */
public record CreateSharedChatMessageRequest(String clientToken, String content, String imageId, String replyToId) {

    /** 滚动发布期间兼容旧调用方和既有测试。 */
    public CreateSharedChatMessageRequest(String clientToken, String content, String imageId) {
        this(clientToken, content, imageId, null);
    }
}
