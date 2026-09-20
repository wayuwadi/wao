package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 任务表实体。富嵌套结构存 {@code detailJson}（JSON 列）。 */
@TableName("task")
public class TaskEntity {

    @TableId
    private String id;
    // 普通任务全文保存不能改动关系，避免旧客户端清空或并发覆盖。
    @com.baomidou.mybatisplus.annotation.TableField(
        insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
        updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private String parentTaskId;
    public String getParentTaskId() { return parentTaskId; }
    public void setParentTaskId(String value) { parentTaskId = value; }
    private String title;
    private String projectId;
    private String status;
    private String pr;
    private String ownerId;
    private String createdBy;
    private LocalDateTime createdAt;
    private String startDate;
    private String dueDate;
    private String waiting;
    private String description;
    private String detailJson;
    private LocalDateTime completedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPr() { return pr; }
    public void setPr(String pr) { this.pr = pr; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public String getDueDate() { return dueDate; }
    public void setDueDate(String dueDate) { this.dueDate = dueDate; }
    public String getWaiting() { return waiting; }
    public void setWaiting(String waiting) { this.waiting = waiting; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
