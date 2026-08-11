package cn.wayuwa.wao.dto;

/** 页面 AI 助手当前是否有可领取问答的本地 Codex 节点。 */
public record AssistantStatusDto(boolean configured, String message) {
}
