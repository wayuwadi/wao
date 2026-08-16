package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 成员按自然周、月、年或至今范围生成的一份证据化人生回顾。 */
@TableName("life_review")
public class LifeReviewEntity {
    @TableId
    private String id;
    private String memberId;
    private String periodType;
    private String periodKey;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private Integer taskCount;
    private Integer assistantConversationCount;
    private Integer chatDayCount;
    private String sourcesJson;
    private String reportJson;
    private LocalDateTime generatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public String getPeriodType() { return periodType; }
    public void setPeriodType(String periodType) { this.periodType = periodType; }
    public String getPeriodKey() { return periodKey; }
    public void setPeriodKey(String periodKey) { this.periodKey = periodKey; }
    public LocalDate getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDate periodStart) { this.periodStart = periodStart; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDate periodEnd) { this.periodEnd = periodEnd; }
    public Integer getTaskCount() { return taskCount; }
    public void setTaskCount(Integer taskCount) { this.taskCount = taskCount; }
    public Integer getAssistantConversationCount() { return assistantConversationCount; }
    public void setAssistantConversationCount(Integer assistantConversationCount) { this.assistantConversationCount = assistantConversationCount; }
    public Integer getChatDayCount() { return chatDayCount; }
    public void setChatDayCount(Integer chatDayCount) { this.chatDayCount = chatDayCount; }
    public String getSourcesJson() { return sourcesJson; }
    public void setSourcesJson(String sourcesJson) { this.sourcesJson = sourcesJson; }
    public String getReportJson() { return reportJson; }
    public void setReportJson(String reportJson) { this.reportJson = reportJson; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
}
