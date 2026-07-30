package cn.wayuwa.wao.dto;

import java.util.List;

/** 一次多轮对话的请求。 */
public record AssistantChatRequest(
        /** 用户本次输入的文本。 */
        String message,
        /** 最近的上下文历史（最多 5 轮）。 */
        List<ChatMessage> history,
        /** 可选：当前页面上下文，用于总结当前页面等指令。 */
        String pageContext) {

    public record ChatMessage(String role, String content) {
    }
}
