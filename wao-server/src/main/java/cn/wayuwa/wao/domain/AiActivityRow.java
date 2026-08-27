package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

/** 首页统一 AI 活动查询行；来自开发运行与聊天复盘的只读 UNION。 */
public class AiActivityRow {
    private String id;
    private String activityType;
    private String title;
    private String status;
    private String subtitle;
    private String capabilityLabel;
    private String targetId;
    private String resultPreview;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getActivityType() { return activityType; }
    public void setActivityType(String activityType) { this.activityType = activityType; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getCapabilityLabel() { return capabilityLabel; }
    public void setCapabilityLabel(String capabilityLabel) { this.capabilityLabel = capabilityLabel; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getResultPreview() { return resultPreview; }
    public void setResultPreview(String resultPreview) { this.resultPreview = resultPreview; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
