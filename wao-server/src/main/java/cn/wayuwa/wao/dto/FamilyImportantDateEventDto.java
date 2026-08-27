package cn.wayuwa.wao.dto;

/** 日历提醒事项在指定年份中的一次真实公历事件。 */
public record FamilyImportantDateEventDto(String id, String importantDateId, String date, String name,
        String note, boolean reminderEnabled, int reminderDaysBefore, String reminderTime,
        String calendarType, String solarDateLabel, String lunarDateLabel,
        String notificationType, String chatName) { }
