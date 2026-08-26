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
        OffsetDateTime finishedAt) { }
