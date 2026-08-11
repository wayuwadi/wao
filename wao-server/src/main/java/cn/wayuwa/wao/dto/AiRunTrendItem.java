package cn.wayuwa.wao.dto;

/**
 * 某一天的 AI 运行频次与真实回传用量。
 *
 * @param recordedRuns      已由 CLI 回传 Token 用量的运行数；只有模型或金额不算 Token 已记账
 * @param cachedInputTokens 缓存读取与缓存写入之和
 */
public record AiRunTrendItem(
        String date,
        int total,
        int succeeded,
        int failed,
        int cancelled,
        int recordedRuns,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long totalTokens) {
}
