package cn.wayuwa.wao.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 一次由本地 AI CLI 执行 WAO 任务的运行记录。 */
@TableName("ai_task_run")
public class AiTaskRunEntity {
    @TableId
    private String id;
    private String taskId;
    /** 本次运行针对的子任务标识；整个主任务的运行为 null。 */
    private String subId;
    private String projectId;
    private String engine;
    private String aiAccountId;
    private String status;
    /** 首页统一活动的稳定来源分类；普通开发默认 FEATURE_DEVELOPMENT。 */
    private String activityType;
    /** 面向用户的代码到部署阶段；与 Runner 进程状态分开，等待部署时运行仍保持 active。 */
    private String deliveryStage;
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
    /** 用户为本次 Codex 运行选择的模型；其它引擎或自定义 API 为 null。 */
    private String requestedModel;
    /** 用户为本次 Codex 运行选择的推理强度；其它引擎或自定义 API 为 null。 */
    private String reasoningEffort;
    /** Codex 是否先形成计划再连续执行；问题由 Runner 指示其自动采用推荐项。 */
    private Boolean planMode;
    /** 是否由 Codex app-server 建立并跟踪原生持久目标。 */
    private Boolean goalMode;
    private String goalObjective;
    private String goalStatus;
    private Long goalTokensUsed;
    private Long goalTimeUsedSeconds;
    /** 入队时固化的项目 Skill 版本清单。 */
    private String capabilityManifestJson;
    /** Runner 对各 Skill 的实际装配结果。 */
    private String capabilityAuditJson;
    /** 本次运行输出 token 最多的模型；CLI 没回传时为 null。 */
    private String model;
    /** 本次运行用到的全部模型，逗号分隔；自动选模型时可能不止一个。 */
    private String models;
    private Long inputTokens;
    private Long outputTokens;
    private Long cachedInputTokens;
    private Long totalTokens;
    /** 折算额度（美元）；Codex 不回传花费，这里会是 null。 */
    private BigDecimal costUsd;
    private Integer exitCode;
    private LocalDateTime createdAt;
    private LocalDateTime claimedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getSubId() { return subId; }
    public void setSubId(String subId) { this.subId = subId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }
    public String getAiAccountId() { return aiAccountId; }
    public void setAiAccountId(String aiAccountId) { this.aiAccountId = aiAccountId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getActivityType() { return activityType; }
    public void setActivityType(String activityType) { this.activityType = activityType; }
    public String getDeliveryStage() { return deliveryStage; }
    public void setDeliveryStage(String deliveryStage) { this.deliveryStage = deliveryStage; }
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
    public String getRequestedModel() { return requestedModel; }
    public void setRequestedModel(String requestedModel) { this.requestedModel = requestedModel; }
    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }
    public Boolean getPlanMode() { return planMode; }
    public void setPlanMode(Boolean planMode) { this.planMode = planMode; }
    public Boolean getGoalMode() { return goalMode; }
    public void setGoalMode(Boolean goalMode) { this.goalMode = goalMode; }
    public String getGoalObjective() { return goalObjective; }
    public void setGoalObjective(String goalObjective) { this.goalObjective = goalObjective; }
    public String getGoalStatus() { return goalStatus; }
    public void setGoalStatus(String goalStatus) { this.goalStatus = goalStatus; }
    public Long getGoalTokensUsed() { return goalTokensUsed; }
    public void setGoalTokensUsed(Long goalTokensUsed) { this.goalTokensUsed = goalTokensUsed; }
    public Long getGoalTimeUsedSeconds() { return goalTimeUsedSeconds; }
    public void setGoalTimeUsedSeconds(Long goalTimeUsedSeconds) { this.goalTimeUsedSeconds = goalTimeUsedSeconds; }
    public String getCapabilityManifestJson() { return capabilityManifestJson; }
    public void setCapabilityManifestJson(String value) { capabilityManifestJson = value; }
    public String getCapabilityAuditJson() { return capabilityAuditJson; }
    public void setCapabilityAuditJson(String value) { capabilityAuditJson = value; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getModels() { return models; }
    public void setModels(String models) { this.models = models; }
    public Long getInputTokens() { return inputTokens; }
    public void setInputTokens(Long inputTokens) { this.inputTokens = inputTokens; }
    public Long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Long outputTokens) { this.outputTokens = outputTokens; }
    public Long getCachedInputTokens() { return cachedInputTokens; }
    public void setCachedInputTokens(Long cachedInputTokens) { this.cachedInputTokens = cachedInputTokens; }
    public Long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Long totalTokens) { this.totalTokens = totalTokens; }
    public BigDecimal getCostUsd() { return costUsd; }
    public void setCostUsd(BigDecimal costUsd) { this.costUsd = costUsd; }
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
