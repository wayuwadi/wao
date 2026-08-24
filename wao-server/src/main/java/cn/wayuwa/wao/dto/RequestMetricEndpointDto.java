package cn.wayuwa.wao.dto;

/** 一个 HTTP 方法与接口路径在窗口内的聚合结果。 */
public record RequestMetricEndpointDto(
        String method,
        String path,
        int requestCount,
        int averageDurationMs,
        int p95DurationMs,
        int slowCount,
        int failedCount) {
}
