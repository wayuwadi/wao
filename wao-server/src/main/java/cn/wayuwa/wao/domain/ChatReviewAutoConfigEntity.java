package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 全局唯一的聊天自动复盘策略。 */
@TableName("chat_review_auto_config")
public class ChatReviewAutoConfigEntity {
    @TableId
    private Integer id;
    private Boolean enabled;
    private Integer intervalSeconds;
    private String rangeKind;
    private Integer rangeValue;
    private String focusText;
    private String aiCapabilityId;
    private LocalDateTime nextFireAt;
    private String lastRunId;
    private String updatedBy;
    private LocalDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getIntervalSeconds() { return intervalSeconds; }
    public void setIntervalSeconds(Integer intervalSeconds) { this.intervalSeconds = intervalSeconds; }
    public String getRangeKind() { return rangeKind; }
    public void setRangeKind(String rangeKind) { this.rangeKind = rangeKind; }
    public Integer getRangeValue() { return rangeValue; }
    public void setRangeValue(Integer rangeValue) { this.rangeValue = rangeValue; }
    public String getFocusText() { return focusText; }
    public void setFocusText(String focusText) { this.focusText = focusText; }
    public String getAiCapabilityId() { return aiCapabilityId; }
    public void setAiCapabilityId(String aiCapabilityId) { this.aiCapabilityId = aiCapabilityId; }
    public LocalDateTime getNextFireAt() { return nextFireAt; }
    public void setNextFireAt(LocalDateTime nextFireAt) { this.nextFireAt = nextFireAt; }
    public String getLastRunId() { return lastRunId; }
    public void setLastRunId(String lastRunId) { this.lastRunId = lastRunId; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
