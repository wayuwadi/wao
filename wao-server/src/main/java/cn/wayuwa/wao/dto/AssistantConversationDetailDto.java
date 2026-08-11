package cn.wayuwa.wao.dto;

import java.util.List;

/** AI 助手会话及其完整消息。 */
public record AssistantConversationDetailDto(
        AssistantConversationDto conversation,
        List<AssistantMessageDto> messages) {
}
