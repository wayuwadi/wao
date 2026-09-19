package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.*;

/** 已在设备上过滤凭据的可见会话片段；字节区间是幂等身份的一部分。 */
public record CodexArchiveBatchRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String deviceId,
        @NotBlank @Size(max = 80) String deviceName,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String sessionId,
        @NotBlank @Size(max = 1000) String project,
        @NotBlank @Size(max = 1000) String source,
        @Min(0) long startOffset,
        @Min(1) long endOffset,
        @Min(0) @Max(100000) int part,
        @NotBlank @Size(max = 180) String title,
        @NotBlank @Size(max = 100000) String content) { }
