package cn.wayuwa.wao.dto;

import java.util.List;

/** WAO 页面展示的一次 AI 运行。 */
public record AiRunDto(
        String id,
        String taskId,
        String taskTitle,
        String projectId,
        String projectName,
        String status,
        String runnerId,
        String runnerName,
        String branchName,
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
