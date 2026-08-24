package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 最近一次真实请求的明细，不含请求体、响应体或查询参数。 */
public record RequestMetricItemDto(
        long id,
        String method,
        String path,
        Integer statusCode,
        int durationMs,
        String networkType,
        int retryAttempt,
        LocalDateTime startedAt) {
}
