package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 机器人的实际引用、投递与收集结果；不包含凭据或消息正文。 */
public record FeishuBotActivityDto(List<Usage> usages, List<Send> recentSends,
        List<Capture> recentCaptures, Receiver receiver, boolean canDelete,
        String replacementId, String replacementName) {
    public record Usage(String kind, String label, long count) { }
    public record Send(String kind, String status, LocalDateTime occurredAt, String errorCode) { }
    public record Capture(String messageId, String captureKind, String status, String targetId,
            LocalDateTime occurredAt, String errorCode) { }
    public record Receiver(boolean enabled, boolean appConfigured, boolean chatConfigured,
            boolean callbackVerified, String appId, String chatId, String chatName,
            String eventUrl, int pollIntervalMinutes) { }
}
