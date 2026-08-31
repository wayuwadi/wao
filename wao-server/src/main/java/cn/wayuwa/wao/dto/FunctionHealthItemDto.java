package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 工作台和页头展示的一项真实功能健康度。 */
public record FunctionHealthItemDto(
        String featureId, int score, int iterationCount, int manualAdjustment,
        long totalOpens, long recentOpens, double recentShare, LocalDateTime lastOpenedAt,
        String insightLevel, String insight) {}
