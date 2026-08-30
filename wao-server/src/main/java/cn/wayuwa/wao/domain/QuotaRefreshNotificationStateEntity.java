package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 一个真实额度来源最近一次已处理的群通知比较基线。 */
@TableName("quota_refresh_notification_state")
public class QuotaRefreshNotificationStateEntity {
    @TableId
    private String sourceKey;
    private String accountLabel;
    private String snapshotJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getSourceKey() { return sourceKey; }
    public void setSourceKey(String sourceKey) { this.sourceKey = sourceKey; }
    public String getAccountLabel() { return accountLabel; }
    public void setAccountLabel(String accountLabel) { this.accountLabel = accountLabel; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
