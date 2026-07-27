package cn.wayuwa.wao.dto;

import java.util.List;

/** WAO 页面展示的一次 AI 运行。 */
public record AiRunDto(
        String id,
        String taskId,
        String taskTitle,
        /** 本次只跑这一条子任务；整个任务的运行为 null。 */
        String subId,
        /** 子任务标题，便于页面直接显示跑的是哪一条。 */
        String subTitle,
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
        /** 本次用掉的模型、token 与折算额度。 */
        AiRunUsageDto usage,
        String sessionId,
        Integer exitCode,
        String createdAt,
        String startedAt,
        String finishedAt) {
}
