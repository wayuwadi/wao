package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 通知设置中一条按上海时间每日触发的真实飞书群提醒。 */
public record NotificationReminderDto(
        String id,
        String title,
        String content,
        String time,
        boolean enabled,
        String lastResult,
        LocalDateTime nextFireAt) {
}
