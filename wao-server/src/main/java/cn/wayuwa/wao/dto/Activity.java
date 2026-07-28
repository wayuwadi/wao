package cn.wayuwa.wao.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 活动时间线条目。
 *
 * @param icon  图标（remixicon 类名）
 * @param color 图标颜色
 * @param text  事件描述。带 {@code by} 时不含主语（如「创建了任务」），由前端按看的人补上
 *              「你」或对方的名字；历史数据里是写死的「你创建了任务」，此时 {@code by} 为空
 * @param time  发生时刻（ISO 8601，带 +08:00 偏移）；历史数据可能是「刚刚」这类现成文案
 * @param by    操作人成员 id；系统事件与历史数据为 null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Activity(String icon, String color, String text, String time, String by) {

    /** 没有明确操作人的活动（系统事件、历史数据）。 */
    public Activity(String icon, String color, String text, String time) {
        this(icon, color, text, time, null);
    }
}
