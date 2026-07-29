package cn.wayuwa.wao.dto;

/**
 * 修改 CalDAV 日历订阅状态。
 *
 * @param enabled 是否启用（参与合并展示与同步）
 */
public record PatchCaldavCalendarRequest(Boolean enabled) {
}
