package cn.wayuwa.wao.dto;

/** 一个可维护的家庭重要日定义。 */
public record FamilyImportantDateDto(String id, String name, String eventDate, String note,
        boolean reminderEnabled, int reminderDaysBefore, String reminderTime) { }
