package cn.wayuwa.wao.dto;

/** 首页展示的最早待办自动派发策略与真实检查状态。 */
public record AiTaskAutoRunPolicyDto(
        boolean enabled,
        int idleHours,
        String status,
        String message,
        String lastActivityAt,
        String nextEligibleAt,
        String lastCheckedAt,
        String lastRunId) {
}
