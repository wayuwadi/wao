package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 当前成员 HTTP 请求体验窗口概览。 */
public record RequestMetricOverviewDto(
        int windowHours,
        LocalDateTime generatedAt,
        int totalRequests,
        int averageDurationMs,
        int p95DurationMs,
        int slowRequests,
        int failedRequests,
        List<RequestMetricEndpointDto> endpoints,
        List<RequestMetricItemDto> recentRequests) {
}
