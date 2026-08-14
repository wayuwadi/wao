package cn.wayuwa.wao.dto;

import java.time.LocalTime;

/** 开启、修改或关闭一个每年重复的节日提醒。 */
public record SaveHolidayReminderRequest(boolean enabled, LocalTime reminderTime) { }
