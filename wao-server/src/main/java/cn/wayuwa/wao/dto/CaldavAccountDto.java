package cn.wayuwa.wao.dto;

import java.util.List;

/**
 * CalDAV 账户视图。密码从不回传，任何形态都不带。
 *
 * @param id         账户 id
 * @param name       账户显示名
 * @param serverUrl  服务器地址
 * @param username   登录用户名
 * @param status     ok / error
 * @param lastSyncAt 上次成功同步时间（yyyy-MM-dd HH:mm），从未同步为 null
 * @param syncError  最近一次同步失败原因，正常时为 null
 * @param calendars  账户下发现的日历
 */
public record CaldavAccountDto(
        String id,
        String name,
        String serverUrl,
        String username,
        String status,
        String lastSyncAt,
        String syncError,
        List<CaldavCalendarDto> calendars) {
}
