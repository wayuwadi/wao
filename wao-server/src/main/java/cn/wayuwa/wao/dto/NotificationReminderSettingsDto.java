package cn.wayuwa.wao.dto;

import java.util.List;

/** 每日提醒列表及当前成员的管理权限。 */
public record NotificationReminderSettingsDto(
        boolean canManageSystem,
        List<NotificationReminderDto> reminders) {
}
