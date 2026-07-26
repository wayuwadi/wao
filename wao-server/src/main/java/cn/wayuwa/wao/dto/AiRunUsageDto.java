package cn.wayuwa.wao.dto;

import java.math.BigDecimal;

/**
 * 一次 AI 运行的用量账单。
 *
 * @param model             输出 token 最多的模型，页面上当作「这次是谁做的」
 * @param models            本次用到的全部模型，逗号分隔；自动选模型时可能不止一个
 * @param cachedInputTokens 缓存读取与缓存写入之和
 * @param costUsd           折算额度（美元）；Codex 不回传花费时为 null
 * @param recorded          CLI 是否真的回传了用量；false 表示这次没记上账，不是「用了 0」
 */
public record AiRunUsageDto(
        String model,
        String models,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long totalTokens,
        BigDecimal costUsd,
        boolean recorded) {
}
