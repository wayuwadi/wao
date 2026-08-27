package cn.wayuwa.wao.dto;

/** 可用于页面问答和聊天复盘的一项真实 AI 能力；响应不包含任何凭据。 */
public record AiCapabilityDto(
        String id,
        String label,
        String provider,
        String model,
        String executionMode,
        boolean available,
        String message) { }
