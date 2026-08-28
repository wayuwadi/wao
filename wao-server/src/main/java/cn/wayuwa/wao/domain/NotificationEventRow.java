package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

/** 通知中心从任务、AI Run 与登录流水统一查询的一行真实事件。 */
public class NotificationEventRow {
    private String eventId;
    private String eventType;
    private String title;
    private String detail;
    private String actorName;
    private LocalDateTime occurredAt;
    private String targetPath;
    private String severity;

    public String getEventId() { return eventId; }
    public void setEventId(String value) { eventId = value; }
    public String getEventType() { return eventType; }
    public void setEventType(String value) { eventType = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getDetail() { return detail; }
    public void setDetail(String value) { detail = value; }
    public String getActorName() { return actorName; }
    public void setActorName(String value) { actorName = value; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime value) { occurredAt = value; }
    public String getTargetPath() { return targetPath; }
    public void setTargetPath(String value) { targetPath = value; }
    public String getSeverity() { return severity; }
    public void setSeverity(String value) { severity = value; }
}
