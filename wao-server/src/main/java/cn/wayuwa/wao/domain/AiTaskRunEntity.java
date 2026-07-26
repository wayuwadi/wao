package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 一次由本地 AI CLI 执行 WAO 任务的运行记录。 */
@TableName("ai_task_run")
public class AiTaskRunEntity {
    @TableId
    private String id;
    private String taskId;
    private String projectId;
    private String engine;
    private String status;
    private String runnerId;
    private String requestedRunnerId;
    private String requestedBy;
    private String promptUrl;
    private String branchName;
    /** 本次重跑要修复的那一次失败运行 id；不是重跑时为 null。 */
    private String retryOfRunId;
    private String resultSummary;
    private String latestProgress;
    private LocalDateTime progressUpdatedAt;
    private String sessionId;
    private String pendingInput;
    private Integer exitCode;
    private LocalDateTime createdAt;
    private LocalDateTime claimedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRunnerId() { return runnerId; }
    public void setRunnerId(String runnerId) { this.runnerId = runnerId; }
    public String getRequestedRunnerId() { return requestedRunnerId; }
    public void setRequestedRunnerId(String requestedRunnerId) { this.requestedRunnerId = requestedRunnerId; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }
    public String getPromptUrl() { return promptUrl; }
    public void setPromptUrl(String promptUrl) { this.promptUrl = promptUrl; }
    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }
    public String getRetryOfRunId() { return retryOfRunId; }
    public void setRetryOfRunId(String retryOfRunId) { this.retryOfRunId = retryOfRunId; }
    public String getResultSummary() { return resultSummary; }
    public void setResultSummary(String resultSummary) { this.resultSummary = resultSummary; }
    public String getLatestProgress() { return latestProgress; }
    public void setLatestProgress(String latestProgress) { this.latestProgress = latestProgress; }
    public LocalDateTime getProgressUpdatedAt() { return progressUpdatedAt; }
    public void setProgressUpdatedAt(LocalDateTime progressUpdatedAt) { this.progressUpdatedAt = progressUpdatedAt; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getPendingInput() { return pendingInput; }
    public void setPendingInput(String pendingInput) { this.pendingInput = pendingInput; }
    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getClaimedAt() { return claimedAt; }
    public void setClaimedAt(LocalDateTime claimedAt) { this.claimedAt = claimedAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
