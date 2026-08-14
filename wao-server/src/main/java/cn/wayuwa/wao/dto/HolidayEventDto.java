package cn.wayuwa.wao.dto;

/** 日期窗内可展示、可追溯且可关联个人提醒的节日事件。 */
public record HolidayEventDto(
        String id,
        String code,
        String date,
        int year,
        String name,
        String category,
        String dayType,
        String internationalCategory,
        String sourceName,
        String sourceUrl,
        boolean remindable,
        boolean reminderEnabled,
        String reminderTime) {

    /** 把当前成员的提醒状态投影到只读节日事件，不改变历法事实。 */
    public HolidayEventDto withReminder(boolean enabled, String time) {
        return new HolidayEventDto(id, code, date, year, name, category, dayType,
                internationalCategory, sourceName, sourceUrl, remindable, enabled, time);
    }
}
