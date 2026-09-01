package cn.wayuwa.wao.dto;

/** 群聊润色机器人配置；Webhook 地址始终留在服务端。 */
public record FeishuTextPolishConfigDto(
        boolean enabled,
        String webhookId,
        String webhookName,
        String mentionName) {
}
