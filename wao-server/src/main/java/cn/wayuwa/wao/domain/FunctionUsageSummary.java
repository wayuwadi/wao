package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

/** 一项功能在当前成员范围内的真实使用聚合。 */
public class FunctionUsageSummary {
    private String featureId;
    private Long totalOpens;
    private Long recentOpens;
    private LocalDateTime lastOpenedAt;
    public String getFeatureId() { return featureId; }
    public void setFeatureId(String value) { featureId = value; }
    public Long getTotalOpens() { return totalOpens; }
    public void setTotalOpens(Long value) { totalOpens = value; }
    public Long getRecentOpens() { return recentOpens; }
    public void setRecentOpens(Long value) { recentOpens = value; }
    public LocalDateTime getLastOpenedAt() { return lastOpenedAt; }
    public void setLastOpenedAt(LocalDateTime value) { lastOpenedAt = value; }
}
