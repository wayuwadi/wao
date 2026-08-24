package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** 单次浏览器 HTTP 请求的脱敏体验指标。 */
public record RequestMetricInput(
        @NotBlank @Size(max = 8) String method,
        @NotBlank @Size(max = 255) String path,
        @Min(100) @Max(599) Integer statusCode,
        @NotNull @Min(0) @Max(600_000) Integer durationMs,
        @Size(max = 16) String networkType,
        @Min(0) @Max(3) Integer retryAttempt,
        @NotNull OffsetDateTime startedAt) {
}
