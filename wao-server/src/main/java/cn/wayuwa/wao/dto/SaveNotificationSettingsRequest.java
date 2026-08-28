package cn.wayuwa.wao.dto;

/** 保存通知策略；非管理员只能提交 personal，system 必须省略。 */
public record SaveNotificationSettingsRequest(NotificationSettingsDto.SystemPolicy system,
        NotificationSettingsDto.PersonalPolicy personal) { }
