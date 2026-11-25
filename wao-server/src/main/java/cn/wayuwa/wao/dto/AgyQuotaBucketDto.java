package cn.wayuwa.wao.dto;

/** 单个 Agy 额度窗口（如周额度或 5 小时额度）。 */
public record AgyQuotaBucketDto(String id, String name, String window, int remainingPercent, Long resetsAt) {
}
