package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** 保存 HTTP 性能 AI 巡检设置。 */
public record SaveHttpPerformanceInspectionConfigRequest(
        boolean enabled,
        @NotBlank String frequency,
        @NotBlank String time,
        @Min(1) @Max(168) int windowHours,
        @Min(1) @Max(20) int topN,
        @Min(1) @Max(20) int maxEndpointsPerRun,
        String notificationWebhookId) {
}
