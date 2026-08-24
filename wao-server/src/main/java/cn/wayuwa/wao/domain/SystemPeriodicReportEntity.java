package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 系统建设维度的一份自然日、周或月真实数据快照。 */
@TableName("system_periodic_report")
public class SystemPeriodicReportEntity {
    @TableId private String id;
    private String periodType;
    private String periodKey;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private Integer projectTotal;
    private Integer taskTotal;
    private Integer openTaskTotal;
    private Integer aiRunTotal;
    private Integer taskCreatedCount;
    private Integer taskCompletedCount;
    private Integer aiRunCount;
    private Integer aiRunSuccessCount;
    private Integer aiRunFailureCount;
    private Integer aiRunMeteredCount;
    private Long totalTokens;
    private String taskSourcesJson;
    private String runSourcesJson;
    private LocalDateTime generatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPeriodType() { return periodType; }
    public void setPeriodType(String periodType) { this.periodType = periodType; }
    public String getPeriodKey() { return periodKey; }
    public void setPeriodKey(String periodKey) { this.periodKey = periodKey; }
    public LocalDate getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDate periodStart) { this.periodStart = periodStart; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDate periodEnd) { this.periodEnd = periodEnd; }
    public Integer getProjectTotal() { return projectTotal; }
    public void setProjectTotal(Integer projectTotal) { this.projectTotal = projectTotal; }
    public Integer getTaskTotal() { return taskTotal; }
    public void setTaskTotal(Integer taskTotal) { this.taskTotal = taskTotal; }
    public Integer getOpenTaskTotal() { return openTaskTotal; }
    public void setOpenTaskTotal(Integer openTaskTotal) { this.openTaskTotal = openTaskTotal; }
    public Integer getAiRunTotal() { return aiRunTotal; }
    public void setAiRunTotal(Integer aiRunTotal) { this.aiRunTotal = aiRunTotal; }
    public Integer getTaskCreatedCount() { return taskCreatedCount; }
    public void setTaskCreatedCount(Integer taskCreatedCount) { this.taskCreatedCount = taskCreatedCount; }
    public Integer getTaskCompletedCount() { return taskCompletedCount; }
    public void setTaskCompletedCount(Integer taskCompletedCount) { this.taskCompletedCount = taskCompletedCount; }
    public Integer getAiRunCount() { return aiRunCount; }
    public void setAiRunCount(Integer aiRunCount) { this.aiRunCount = aiRunCount; }
    public Integer getAiRunSuccessCount() { return aiRunSuccessCount; }
    public void setAiRunSuccessCount(Integer aiRunSuccessCount) { this.aiRunSuccessCount = aiRunSuccessCount; }
    public Integer getAiRunFailureCount() { return aiRunFailureCount; }
    public void setAiRunFailureCount(Integer aiRunFailureCount) { this.aiRunFailureCount = aiRunFailureCount; }
    public Integer getAiRunMeteredCount() { return aiRunMeteredCount; }
    public void setAiRunMeteredCount(Integer aiRunMeteredCount) { this.aiRunMeteredCount = aiRunMeteredCount; }
    public Long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Long totalTokens) { this.totalTokens = totalTokens; }
    public String getTaskSourcesJson() { return taskSourcesJson; }
    public void setTaskSourcesJson(String taskSourcesJson) { this.taskSourcesJson = taskSourcesJson; }
    public String getRunSourcesJson() { return runSourcesJson; }
    public void setRunSourcesJson(String runSourcesJson) { this.runSourcesJson = runSourcesJson; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
}
