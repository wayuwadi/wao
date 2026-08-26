package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 全局“最早待办自动派发”策略及最近一次检查结果。 */
@TableName("ai_task_auto_run_state")
public class AiTaskAutoRunStateEntity {
    @TableId
    private String id;
    private Boolean enabled;
    private Integer idleHours;
    private String projectId;
    private String taskStatusesJson;
    private String notificationWebhookId;
    private LocalDateTime nextCheckAt;
    private LocalDateTime lastCheckedAt;
    private String lastOutcome;
    private String lastRunId;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getIdleHours() { return idleHours; }
    public void setIdleHours(Integer idleHours) { this.idleHours = idleHours; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getTaskStatusesJson() { return taskStatusesJson; }
    public void setTaskStatusesJson(String taskStatusesJson) { this.taskStatusesJson = taskStatusesJson; }
    public String getNotificationWebhookId() { return notificationWebhookId; }
    public void setNotificationWebhookId(String notificationWebhookId) { this.notificationWebhookId = notificationWebhookId; }
    public LocalDateTime getNextCheckAt() { return nextCheckAt; }
    public void setNextCheckAt(LocalDateTime nextCheckAt) { this.nextCheckAt = nextCheckAt; }
    public LocalDateTime getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(LocalDateTime lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }
    public String getLastOutcome() { return lastOutcome; }
    public void setLastOutcome(String lastOutcome) { this.lastOutcome = lastOutcome; }
    public String getLastRunId() { return lastRunId; }
    public void setLastRunId(String lastRunId) { this.lastRunId = lastRunId; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
