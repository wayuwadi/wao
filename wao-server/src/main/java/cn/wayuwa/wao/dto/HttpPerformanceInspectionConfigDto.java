package cn.wayuwa.wao.dto;

/** HTTP 性能 AI 巡检当前生效的计划、数据范围和通知配置。 */
public record HttpPerformanceInspectionConfigDto(
        boolean enabled,
        String frequency,
        String time,
        int windowHours,
        int topN,
        int maxEndpointsPerRun,
        String notificationWebhookId) {
}
