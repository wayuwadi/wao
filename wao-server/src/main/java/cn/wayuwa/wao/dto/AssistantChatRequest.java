package cn.wayuwa.wao.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 一次多轮对话的请求。 */
public record AssistantChatRequest(
        /** 用户本次输入的文本。 */
        @NotBlank @Size(max = 4000) String message,
        /** 最近的上下文历史（最多 5 轮）。 */
        @Size(max = 10) List<@Valid ChatMessage> history,
        /** 可选：当前页面上下文，用于总结当前页面等指令。 */
        @Size(max = 10000) String pageContext,
        /** 可选：消息所属的持久化会话；旧客户端省略时自动创建新会话。 */
        @Size(max = 32) String conversationId,
        /** 可选：限定本次检索的资产类型、群聊、发言人、日期和项目。 */
        @Valid AssistantSourceFilter sourceFilter,
        /** 可选：本次固定使用的 AI 能力；省略时使用本地 Codex。 */
        @Size(max = 64) String aiCapabilityId) {

    public AssistantChatRequest(String message, List<ChatMessage> history, String pageContext) {
        this(message, history, pageContext, null, null, null);
    }

    public AssistantChatRequest(String message, List<ChatMessage> history, String pageContext,
            String conversationId) {
        this(message, history, pageContext, conversationId, null, null);
    }

    public AssistantChatRequest(String message, List<ChatMessage> history, String pageContext,
            String conversationId, AssistantSourceFilter sourceFilter) {
        this(message, history, pageContext, conversationId, sourceFilter, null);
    }

    public record ChatMessage(
            @NotBlank @Pattern(regexp = "user|assistant") String role,
            @NotBlank @Size(max = 4000) String content) {
    }
}
