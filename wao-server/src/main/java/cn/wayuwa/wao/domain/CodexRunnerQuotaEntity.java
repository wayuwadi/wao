package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;

/** 一台 Runner 最近一次本地 Codex 额度检查；凭据始终不离开执行电脑。 */
@TableName("codex_runner_quota")
public class CodexRunnerQuotaEntity {
    @TableId
    private String runnerId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String accountEmail;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String plan;
    private String snapshotJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;
    private LocalDateTime lastSuccessAt;
    private LocalDateTime lastCheckedAt;

    public String getRunnerId() { return runnerId; }
    public void setRunnerId(String runnerId) { this.runnerId = runnerId; }
    public String getAccountEmail() { return accountEmail; }
    public void setAccountEmail(String accountEmail) { this.accountEmail = accountEmail; }
    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getLastSuccessAt() { return lastSuccessAt; }
    public void setLastSuccessAt(LocalDateTime lastSuccessAt) { this.lastSuccessAt = lastSuccessAt; }
    public LocalDateTime getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(LocalDateTime lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }
}
