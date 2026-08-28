package cn.wayuwa.wao.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 当前成员的通知中心快照。 */
public record NotificationCenterDto(List<NotificationEventDto> events,
        List<MemberLoginUsageDto> loginUsage, long unreadCount, OffsetDateTime readAt) { }
