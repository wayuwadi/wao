package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 自动化任务表实体。 */
@TableName("automation_job")
public class AutomationJobEntity {

    @TableId
    private String id;
    private String name;
    private String icon;
    private String scheduleLabel;
    private String cronExpr;
    private String status;
    private String lastRunLabel;
    private String description;
    private String source;
    private String target;
    private String stepsJson;
    private LocalDateTime nextFireAt;
    private Integer ord;
    /** 真实执行器名；为空表示没有接外部系统，运行记录由模拟执行产生。 */
    private String handler;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public String getScheduleLabel() { return scheduleLabel; }
    public void setScheduleLabel(String scheduleLabel) { this.scheduleLabel = scheduleLabel; }
    public String getCronExpr() { return cronExpr; }
    public void setCronExpr(String cronExpr) { this.cronExpr = cronExpr; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLastRunLabel() { return lastRunLabel; }
    public void setLastRunLabel(String lastRunLabel) { this.lastRunLabel = lastRunLabel; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getStepsJson() { return stepsJson; }
    public void setStepsJson(String stepsJson) { this.stepsJson = stepsJson; }
    public LocalDateTime getNextFireAt() { return nextFireAt; }
    public void setNextFireAt(LocalDateTime nextFireAt) { this.nextFireAt = nextFireAt; }
    public Integer getOrd() { return ord; }
    public void setOrd(Integer ord) { this.ord = ord; }
    public String getHandler() { return handler; }
    public void setHandler(String handler) { this.handler = handler; }
}
