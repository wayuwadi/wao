package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/**
 * 一项可复用的飞书自定义机器人配置。Webhook 明文永不出服务端，只返回脱敏形态和是否已设置。
 */
public record FeishuWebhookEndpointDto(
        String id,
        String name,
        String description,
        String urlMask,
        boolean urlSet,
        boolean enabled,
        boolean systemDefault,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String captureKind,
        String mentionId,
        String mentionName,
        String chatId,
        String appId,
        long revision) {
    /** 兼容未接入机器人收集的内部调用。 */
    public FeishuWebhookEndpointDto(String id, String name, String description, String urlMask,
            boolean urlSet, boolean enabled, boolean systemDefault, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, name, description, urlMask, urlSet, enabled, systemDefault, createdAt, updatedAt,
                "NONE", "", "", "", "", 0);
    }
}
