package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 系统周期报告中的任务活动快照。 */
public record SystemReportTaskDto(String id, String title, String projectId, String projectName,
        String status, List<String> events, LocalDateTime createdAt, LocalDateTime completedAt) { }
