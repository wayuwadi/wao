package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** AI 助手历史会话中的一条消息。 */
public record AssistantMessageDto(
        String id,
        String role,
        String content,
        LocalDateTime createdAt) {
}
