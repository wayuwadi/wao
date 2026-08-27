package cn.wayuwa.wao.dto;

/** 页面 AI 助手所选能力当前是否可执行。 */
public record AssistantStatusDto(boolean configured, String message,
        String capabilityId, String capabilityLabel) {

    public AssistantStatusDto(boolean configured, String message) {
        this(configured, message, null, null);
    }
}
