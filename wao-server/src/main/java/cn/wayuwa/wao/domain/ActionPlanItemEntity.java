package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 行动计划内一条有星期规则的行动。 */
@TableName("action_plan_item")
public class ActionPlanItemEntity {
    @TableId private String id;
    private String planId;
    private String title;
    private String weekdaysJson;
    private Integer ord;
    private Boolean active;
    private LocalDate activeFrom;
    private LocalDate activeUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getWeekdaysJson() { return weekdaysJson; }
    public void setWeekdaysJson(String weekdaysJson) { this.weekdaysJson = weekdaysJson; }
    public Integer getOrd() { return ord; }
    public void setOrd(Integer ord) { this.ord = ord; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
    public LocalDate getActiveFrom() { return activeFrom; }
    public void setActiveFrom(LocalDate activeFrom) { this.activeFrom = activeFrom; }
    public LocalDate getActiveUntil() { return activeUntil; }
    public void setActiveUntil(LocalDate activeUntil) { this.activeUntil = activeUntil; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
