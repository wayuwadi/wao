package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** AI 助手历史会话摘要。 */
public record AssistantConversationDto(
        String id,
        String title,
        String preview,
        LocalDateTime updatedAt) {
}
