package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 一次共享聊天复盘的完整进度、报告和来源。 */
public record ChatReviewRunDetailDto(
        String id,
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
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String chatId,
        String chatName,
        List<ChatReviewSpeakerDto> speakers,
        String aiRunId,
        List<ChatReviewSourceDto> sources,
        ChatReviewReportDto report) { }
