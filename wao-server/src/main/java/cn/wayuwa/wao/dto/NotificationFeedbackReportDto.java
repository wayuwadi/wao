package cn.wayuwa.wao.dto;

import java.util.List;

/** 飞书提醒反馈报表：没有点击的通知不会被伪造为任何结果。 */
public record NotificationFeedbackReportDto(String period, int doneCount, int missedCount, int totalCount,
        List<Row> rows) {
    public record Row(String notificationAt, String event, String operator, String action) { }
}
