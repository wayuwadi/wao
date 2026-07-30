package cn.wayuwa.wao.dto;

/** 仪表盘顶部指标卡片。 */
public record DashboardSummaryDto(
        int totalRuns,
        int activeRuns,
        int onlineRunners,
        int completedThisWeek,
        int succeededThisWeek,
        int failedThisWeek) {
}
