package cn.wayuwa.wao.dto;

import java.util.List;

/** 数据统计页的脱敏真实额度趋势与刷新事件。 */
public record AiQuotaHistoryDto(
        int recordedSamples,
        List<Series> series,
        List<RefreshEvent> refreshes,
        List<OfficialResetEvent> officialResets) {

    public record Series(
            String id,
            String accountLabel,
            String provider,
            String windowLabel,
            List<DailyPoint> points) {
    }

    public record DailyPoint(String date, int remainingPercent, int decreasedPercent) {
    }

    public record RefreshEvent(
            String accountLabel,
            String provider,
            String windowLabel,
            int beforeRemainingPercent,
            int afterRemainingPercent,
            int refreshedPercent,
            String resetsAt,
            String observedAt) {
    }

    /** Codex 七天窗口由供应商时间推进确认的一次官方自动重置。 */
    public record OfficialResetEvent(
            String accountLabel,
            String windowLabel,
            int beforeRemainingPercent,
            int afterRemainingPercent,
            String resetAt,
            String confirmedAt) {
    }
}
