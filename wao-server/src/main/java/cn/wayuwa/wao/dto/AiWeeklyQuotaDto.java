package cn.wayuwa.wao.dto;

/** 首页展示的一条真实七天额度快照。百分比未知时不返回该记录。 */
public record AiWeeklyQuotaDto(
        String accountId,
        String provider,
        String label,
        String plan,
        int usedPercent,
        int remainingPercent,
        String resetsAt,
        String lastSyncedAt,
        String status,
        String message) {
}
