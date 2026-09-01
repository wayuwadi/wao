package cn.wayuwa.wao.dto;

/** 转发与取码集成状态；令牌明文永不从读取接口返回。 */
public record AccountResourceIntegrationDto(boolean ingestTokenConfigured, boolean crawlerTokenConfigured,
        String notificationWebhookId, String ingestPath, String latestCodePath) { }
