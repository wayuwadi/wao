package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 一次不含供应商凭据或原始响应的真实 AI 额度采样。 */
@TableName("ai_quota_snapshot")
public class AiQuotaSnapshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sourceKey;
    private String accountLabel;
    private String provider;
    private String windowKey;
    private String windowLabel;
    private Integer remainingPercent;
    private LocalDateTime resetsAt;
    private LocalDateTime observedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSourceKey() { return sourceKey; }
    public void setSourceKey(String sourceKey) { this.sourceKey = sourceKey; }
    public String getAccountLabel() { return accountLabel; }
    public void setAccountLabel(String accountLabel) { this.accountLabel = accountLabel; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getWindowKey() { return windowKey; }
    public void setWindowKey(String windowKey) { this.windowKey = windowKey; }
    public String getWindowLabel() { return windowLabel; }
    public void setWindowLabel(String windowLabel) { this.windowLabel = windowLabel; }
    public Integer getRemainingPercent() { return remainingPercent; }
    public void setRemainingPercent(Integer remainingPercent) { this.remainingPercent = remainingPercent; }
    public LocalDateTime getResetsAt() { return resetsAt; }
    public void setResetsAt(LocalDateTime resetsAt) { this.resetsAt = resetsAt; }
    public LocalDateTime getObservedAt() { return observedAt; }
    public void setObservedAt(LocalDateTime observedAt) { this.observedAt = observedAt; }
}
