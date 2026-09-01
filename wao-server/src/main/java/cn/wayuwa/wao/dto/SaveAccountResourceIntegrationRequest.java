package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Size;

/** 保存验证码转发目标；空值表示使用系统默认飞书机器人。 */
public record SaveAccountResourceIntegrationRequest(
        @Size(max = 32, message = "Webhook id 不能超过 32 个字符") String notificationWebhookId) { }
