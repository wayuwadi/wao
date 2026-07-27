package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 项目表实体。富嵌套结构存 {@code detailJson}（JSON 列），由 Service 组装为 DTO。 */
@TableName("project")
public class ProjectEntity {

    @TableId
    private String id;
    private String name;
    private String area;
    private String status;
    private String health;
    private Integer progress;
    private String ownerId;
    private String startDate;
    private String dueDate;
    private String visibility;
    private String updatedLabel;
    private String goal;
    private String detailJson;
    /** 这个项目的动静要不要发到飞书群；老数据为空时按开处理。 */
    private Boolean notifyEnabled;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getHealth() { return health; }
    public void setHealth(String health) { this.health = health; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public String getDueDate() { return dueDate; }
    public void setDueDate(String dueDate) { this.dueDate = dueDate; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public String getUpdatedLabel() { return updatedLabel; }
    public void setUpdatedLabel(String updatedLabel) { this.updatedLabel = updatedLabel; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }
    public Boolean getNotifyEnabled() { return notifyEnabled; }
    public void setNotifyEnabled(Boolean notifyEnabled) { this.notifyEnabled = notifyEnabled; }
}
