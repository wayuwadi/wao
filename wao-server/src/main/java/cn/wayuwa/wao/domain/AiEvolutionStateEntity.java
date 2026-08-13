package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 双节点共享的自主进化检查与最近派发状态。 */
@TableName("ai_evolution_state")
public class AiEvolutionStateEntity {
    @TableId
    private String id;
    private LocalDateTime nextCheckAt;
    private LocalDateTime lastCheckedAt;
    private String lastOutcome;
    private String lastRunId;
    private String lastSuggestionId;
    private String lastLowQuotaKey;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public LocalDateTime getNextCheckAt() { return nextCheckAt; }
    public void setNextCheckAt(LocalDateTime nextCheckAt) { this.nextCheckAt = nextCheckAt; }
    public LocalDateTime getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(LocalDateTime lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }
    public String getLastOutcome() { return lastOutcome; }
    public void setLastOutcome(String lastOutcome) { this.lastOutcome = lastOutcome; }
    public String getLastRunId() { return lastRunId; }
    public void setLastRunId(String lastRunId) { this.lastRunId = lastRunId; }
    public String getLastSuggestionId() { return lastSuggestionId; }
    public void setLastSuggestionId(String lastSuggestionId) { this.lastSuggestionId = lastSuggestionId; }
    public String getLastLowQuotaKey() { return lastLowQuotaKey; }
    public void setLastLowQuotaKey(String lastLowQuotaKey) { this.lastLowQuotaKey = lastLowQuotaKey; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
