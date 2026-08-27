package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 聊天复盘活动运行或历史的轻量摘要。 */
public record ChatReviewRunSummaryDto(
        String id,
        String sourceKind,
        String rangeKind,
        Integer rangeValue,
        LocalDate startDate,
        LocalDate endDate,
        String focus,
        String aiCapabilityId,
        String triggerKind,
        String stage,
        int progressCurrent,
        int progressTotal,
        LocalDate currentDate,
        String failedStage,
        LocalDate failedDate,
        String error,
        int messageCount,
        String createdById,
        String createdByName,
        String reportUrl,
        String knowledgeUrl,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {

    /** 兼容未记录 AI 能力与触发方式的旧调用方。 */
    public ChatReviewRunSummaryDto(String id, String sourceKind, String rangeKind, Integer rangeValue,
            LocalDate startDate, LocalDate endDate, String focus, String stage,
            int progressCurrent, int progressTotal, LocalDate currentDate, String failedStage,
            LocalDate failedDate, String error, int messageCount, String createdById,
            String createdByName, String reportUrl, String knowledgeUrl, OffsetDateTime createdAt,
            OffsetDateTime startedAt, OffsetDateTime finishedAt) {
        this(id, sourceKind, rangeKind, rangeValue, startDate, endDate, focus,
                "local-codex", "MANUAL", stage, progressCurrent, progressTotal, currentDate,
                failedStage, failedDate, error, messageCount, createdById, createdByName,
                reportUrl, knowledgeUrl, createdAt, startedAt, finishedAt);
    }
}
