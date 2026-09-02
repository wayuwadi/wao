package cn.wayuwa.wao.dto;

/** 保存群聊润色机器人配置。 */
public record SaveFeishuTextPolishConfigRequest(
        Boolean enabled,
        String webhookId,
        String mentionName) {
}
