package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 系统周期报告中的 AI Run 活动快照。 */
public record SystemReportRunDto(String id, String taskId, String taskTitle, String projectId,
        String projectName, String engine, String status, String resultSummary, Long totalTokens,
        LocalDateTime createdAt, LocalDateTime finishedAt) { }
