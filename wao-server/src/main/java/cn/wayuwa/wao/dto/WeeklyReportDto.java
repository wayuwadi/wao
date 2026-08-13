package cn.wayuwa.wao.dto;

/** 只基于用户原始记录整理出的四段结构化周报。 */
public record WeeklyReportDto(
        String completed,
        String progress,
        String risks,
        String nextPlan) {
}
