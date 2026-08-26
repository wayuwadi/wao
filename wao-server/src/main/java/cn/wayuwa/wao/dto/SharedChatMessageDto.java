package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 共享聊天室消息，不暴露飞书地址或客户端去重 token。 */
public record SharedChatMessageDto(
        String id,
        MemberDto author,
        String content,
        String imageId,
        String imageName,
        String imageUrl,
        String feishuStatus,
        String feishuError,
        LocalDateTime createdAt) {
}
