package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

/** 一条任务最近一次 AI Run 的只读查询结果。 */
public class ReviewableTaskRunRow {
    private String reviewTaskId;
    private String runId;
    private String status;
    private String deliveryStage;
    private String resultSummary;
    private LocalDateTime finishedAt;

    public String getReviewTaskId() { return reviewTaskId; }
    public void setReviewTaskId(String reviewTaskId) { this.reviewTaskId = reviewTaskId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDeliveryStage() { return deliveryStage; }
    public void setDeliveryStage(String deliveryStage) { this.deliveryStage = deliveryStage; }
    public String getResultSummary() { return resultSummary; }
    public void setResultSummary(String resultSummary) { this.resultSummary = resultSummary; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
