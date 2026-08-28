package cn.wayuwa.wao.dto;

import java.util.List;

/** 首页展示的最早待办自动派发策略与真实检查状态。 */
public record AiTaskAutoRunPolicyDto(
        boolean enabled,
        int idleHours,
        int batchSize,
        String projectId,
        List<String> taskStatuses,
        String notificationWebhookId,
        String status,
        String message,
        String lastActivityAt,
        String nextEligibleAt,
        String lastCheckedAt,
        String lastRunId) {
}
