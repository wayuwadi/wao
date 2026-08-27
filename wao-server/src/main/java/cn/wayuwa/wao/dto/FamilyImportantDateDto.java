package cn.wayuwa.wao.dto;

/** 一个可维护的日历提醒事项定义。 */
public record FamilyImportantDateDto(String id, String name, String eventDate, String note,
        boolean reminderEnabled, int reminderDaysBefore, String reminderTime,
        String calendarType, Integer lunarMonth, Integer lunarDay, boolean lunarLeapMonth,
        String solarDateLabel, String lunarDateLabel, String notificationType,
        String chatId, String chatName) {

    /** 兼容既有控制器测试和调用。 */
    public FamilyImportantDateDto(String id, String name, String eventDate, String note,
            boolean reminderEnabled, int reminderDaysBefore, String reminderTime) {
        this(id, name, eventDate, note, reminderEnabled, reminderDaysBefore, reminderTime,
                "SOLAR", null, null, false, eventDate, null, "DIRECT", null, null);
    }
}
