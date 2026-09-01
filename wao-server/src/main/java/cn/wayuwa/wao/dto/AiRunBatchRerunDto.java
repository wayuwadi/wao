package cn.wayuwa.wao.dto;

import java.util.List;

/** 一次同原因批量重跑的真实处理结果。 */
public record AiRunBatchRerunDto(
        String failureCode,
        String failureLabel,
        int matchedCount,
        int queuedCount,
        int skippedCount,
        List<String> runIds) { }
