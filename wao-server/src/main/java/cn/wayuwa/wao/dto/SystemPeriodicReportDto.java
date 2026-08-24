package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 一个系统自然周期的范围、快照统计和可追溯活动明细。 */
public record SystemPeriodicReportDto(
        String period, String periodKey, String periodLabel, LocalDate startDate, LocalDate endDate,
        int projectTotal, int taskTotal, int openTaskTotal, int aiRunTotal,
        int taskCreatedCount, int taskCompletedCount, int aiRunCount,
        int aiRunSuccessCount, int aiRunFailureCount, int aiRunMeteredCount, long totalTokens,
        List<SystemReportTaskDto> tasks, List<SystemReportRunDto> runs,
        LocalDateTime generatedAt, String coverageMessage) { }
