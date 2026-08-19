package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 共享聊天复盘的持久化运行；成功终态即是一份历史报告。 */
@TableName("chat_review_run")
public class ChatReviewRunEntity {
    @TableId
    private String id;
    private String chatId;
    private String chatName;
    private String kbId;
    private String speakerOneId;
    private String speakerOneName;
    private String speakerTwoId;
    private String speakerTwoName;
    private LocalDate startDate;
    private LocalDate endDate;
    private String focusText;
    private String stage;
    private String failedStage;
    private Integer progressCurrent;
    private Integer progressTotal;
    /** API 保持 currentDate 语义；数据库避开 MySQL 保留字 CURRENT_DATE。 */
    @TableField("processing_date")
    private LocalDate currentDate;
    private LocalDate failedDate;
    private Integer messageCount;
    private String aiRunId;
    private String retryOfId;
    private String sourcesJson;
    private String reportJson;
    private String errorText;
    private String createdBy;
    private String createdByName;
    private String workerToken;
    private LocalDateTime leaseUntil;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
    public String getChatName() { return chatName; }
    public void setChatName(String chatName) { this.chatName = chatName; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getSpeakerOneId() { return speakerOneId; }
    public void setSpeakerOneId(String speakerOneId) { this.speakerOneId = speakerOneId; }
    public String getSpeakerOneName() { return speakerOneName; }
    public void setSpeakerOneName(String speakerOneName) { this.speakerOneName = speakerOneName; }
    public String getSpeakerTwoId() { return speakerTwoId; }
    public void setSpeakerTwoId(String speakerTwoId) { this.speakerTwoId = speakerTwoId; }
    public String getSpeakerTwoName() { return speakerTwoName; }
    public void setSpeakerTwoName(String speakerTwoName) { this.speakerTwoName = speakerTwoName; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public String getFocusText() { return focusText; }
    public void setFocusText(String focusText) { this.focusText = focusText; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getFailedStage() { return failedStage; }
    public void setFailedStage(String failedStage) { this.failedStage = failedStage; }
    public Integer getProgressCurrent() { return progressCurrent; }
    public void setProgressCurrent(Integer progressCurrent) { this.progressCurrent = progressCurrent; }
    public Integer getProgressTotal() { return progressTotal; }
    public void setProgressTotal(Integer progressTotal) { this.progressTotal = progressTotal; }
    public LocalDate getCurrentDate() { return currentDate; }
    public void setCurrentDate(LocalDate currentDate) { this.currentDate = currentDate; }
    public LocalDate getFailedDate() { return failedDate; }
    public void setFailedDate(LocalDate failedDate) { this.failedDate = failedDate; }
    public Integer getMessageCount() { return messageCount; }
    public void setMessageCount(Integer messageCount) { this.messageCount = messageCount; }
    public String getAiRunId() { return aiRunId; }
    public void setAiRunId(String aiRunId) { this.aiRunId = aiRunId; }
    public String getRetryOfId() { return retryOfId; }
    public void setRetryOfId(String retryOfId) { this.retryOfId = retryOfId; }
    public String getSourcesJson() { return sourcesJson; }
    public void setSourcesJson(String sourcesJson) { this.sourcesJson = sourcesJson; }
    public String getReportJson() { return reportJson; }
    public void setReportJson(String reportJson) { this.reportJson = reportJson; }
    public String getErrorText() { return errorText; }
    public void setErrorText(String errorText) { this.errorText = errorText; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
    public String getWorkerToken() { return workerToken; }
    public void setWorkerToken(String workerToken) { this.workerToken = workerToken; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(LocalDateTime leaseUntil) { this.leaseUntil = leaseUntil; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
