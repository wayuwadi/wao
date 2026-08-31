package cn.wayuwa.wao.dto;

/** 新建或完整保存一条每日提醒的草稿。 */
public record SaveNotificationReminderRequest(
        String title,
        String content,
        String time,
        boolean enabled) {
}
