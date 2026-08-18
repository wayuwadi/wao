package cn.wayuwa.wao.dto;

/** 家庭重要日在指定年份中的一次日历事件。 */
public record FamilyImportantDateEventDto(String id, String importantDateId, String date, String name,
        String note, boolean reminderEnabled, int reminderDaysBefore, String reminderTime) { }
