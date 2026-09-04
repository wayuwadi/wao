package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

/** 群卡片投递快照；目标仅为稳定 id，不保存凭据。 */
public record TianrenNotificationEntity(String readingId, String state, String channel,
        String targetId, String targetName, String payload, String messageId,
        String message, LocalDateTime updatedAt) { }
