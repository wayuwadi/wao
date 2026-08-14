package cn.wayuwa.wao.dto;

/** 一个节日在当前成员名下的提醒状态。 */
public record HolidayReminderDto(String holidayCode, boolean enabled, String reminderTime) { }
