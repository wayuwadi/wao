package cn.wayuwa.wao.dto;

/**
 * CalDAV 订阅日历视图。
 *
 * @param id      日历 id
 * @param name    日历名
 * @param color   展示颜色（#RRGGBB）
 * @param enabled 是否参与合并展示与同步
 */
public record CaldavCalendarDto(String id, String name, String color, boolean enabled) {
}
