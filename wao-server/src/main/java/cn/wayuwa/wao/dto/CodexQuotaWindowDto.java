package cn.wayuwa.wao.dto;

/** Codex 一个真实限额窗口；重置时间使用供应商返回的 Unix 秒。 */
public record CodexQuotaWindowDto(
        int usedPercent,
        Long windowDurationMinutes,
        Long resetsAt) {
}
