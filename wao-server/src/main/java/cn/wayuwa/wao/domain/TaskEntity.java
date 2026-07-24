package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 任务表实体。富嵌套结构存 {@code detailJson}（JSON 列）。 */
@TableName("task")
public class TaskEntity {

    @TableId
    private String id;
    private String title;
    private String projectId;
    private String status;
    private String pr;
    private String ownerId;
    private String startDate;
    private String dueDate;
    private String waiting;
    private String description;
    private String detailJson;

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
}
