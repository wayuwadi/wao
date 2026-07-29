package cn.wayuwa.wao.dto;

/**
 * 日历事件视图（CalDAV 同步副本）。
 *
 * @param id           事件 id
 * @param calendarId   所属日历 id
 * @param calendarName 所属日历名
 * @param color        所属日历颜色
 * @param summary      标题
 * @param location     地点，可空
 * @param start        开始（yyyy-MM-dd HH:mm）
 * @param end          结束（yyyy-MM-dd HH:mm），可空
 * @param allDay       是否全天
 */
public record CaldavEventDto(
        String id,
        String calendarId,
        String calendarName,
        String color,
        String summary,
        String location,
        String start,
        String end,
        boolean allDay) {
}
