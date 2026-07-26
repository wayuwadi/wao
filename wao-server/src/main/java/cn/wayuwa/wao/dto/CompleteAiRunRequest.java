package cn.wayuwa.wao.dto;

import java.math.BigDecimal;

/**
 * 节点完成 AI CLI 进程后的结果回传。
 *
 * <p>用量字段来自 CLI 的结束事件；Codex 不回传花费，{@code costUsd} 会是 null。
 * 统计解析失败时 Runner 会回传空用量，不影响退出码和结果摘要。
 */
public record CompleteAiRunRequest(
        Integer exitCode,
        String resultSummary,
        String sessionId,
        String model,
        String models,
        Long inputTokens,
        Long outputTokens,
        Long cachedInputTokens,
        Long totalTokens,
        BigDecimal costUsd) {
}
