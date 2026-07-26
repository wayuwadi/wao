package cn.wayuwa.wao.dto;

import java.util.List;

/** WAO 页面展示的一次 AI 运行。 */
public record AiRunDto(
        String id,
        String taskId,
        String taskTitle,
        String projectId,
        String projectName,
        String engine,
        String status,
        String runnerId,
        String runnerName,
        String requestedRunnerId,
        String branchName,
        /** 本次是在修哪一次失败的运行；不是重跑时为 null。 */
        String retryOfRunId,
        String resultSummary,
        String latestProgress,
        String progressUpdatedAt,
        List<AiRunProgressDto> progress,
        String sessionId,
        Integer exitCode,
        String createdAt,
        String startedAt,
        String finishedAt) {
}
