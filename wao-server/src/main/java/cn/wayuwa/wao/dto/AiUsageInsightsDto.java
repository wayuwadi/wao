package cn.wayuwa.wao.dto;

import java.util.List;

/** 根据已落库 AI Run 轨迹生成的可解释使用建议；数据不足时不伪装成最优结论。 */
public record AiUsageInsightsDto(
        int windowDays,
        int totalRuns,
        int completedRuns,
        int meteredRuns,
        Long medianDurationSeconds,
        List<Advice> advice) {

    public record Advice(
            String category,
            String title,
            String recommendation,
            String evidence,
            boolean sufficientData) {
    }
}
