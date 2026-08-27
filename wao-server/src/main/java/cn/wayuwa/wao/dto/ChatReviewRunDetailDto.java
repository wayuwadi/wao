package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 一次共享聊天复盘的完整进度、报告和来源。 */
public record ChatReviewRunDetailDto(
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
        OffsetDateTime finishedAt,
        String chatId,
        String chatName,
        List<ChatReviewSpeakerDto> speakers,
        String aiRunId,
        List<ChatReviewSourceDto> sources,
        ChatReviewReportDto report) {

    /** 兼容未记录 AI 能力与触发方式的旧调用方。 */
    public ChatReviewRunDetailDto(String id, String sourceKind, String rangeKind, Integer rangeValue,
            LocalDate startDate, LocalDate endDate, String focus, String stage,
            int progressCurrent, int progressTotal, LocalDate currentDate, String failedStage,
            LocalDate failedDate, String error, int messageCount, String createdById,
            String createdByName, String reportUrl, String knowledgeUrl, OffsetDateTime createdAt,
            OffsetDateTime startedAt, OffsetDateTime finishedAt, String chatId, String chatName,
            List<ChatReviewSpeakerDto> speakers, String aiRunId, List<ChatReviewSourceDto> sources,
            ChatReviewReportDto report) {
        this(id, sourceKind, rangeKind, rangeValue, startDate, endDate, focus,
                "local-codex", "MANUAL", stage, progressCurrent, progressTotal, currentDate,
                failedStage, failedDate, error, messageCount, createdById, createdByName,
                reportUrl, knowledgeUrl, createdAt, startedAt, finishedAt, chatId, chatName,
                speakers, aiRunId, sources, report);
    }
}
