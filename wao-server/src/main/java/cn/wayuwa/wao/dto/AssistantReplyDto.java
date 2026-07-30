package cn.wayuwa.wao.dto;

/** 助手单条回复。 */
public record AssistantReplyDto(
        String id,
        String content,
        /** 'ok' | 'no_config' | 'error'。前端据此展示不同样式。 */
        String status) {
}
