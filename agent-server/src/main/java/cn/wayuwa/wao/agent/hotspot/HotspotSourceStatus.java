package cn.wayuwa.wao.agent.hotspot;

import java.time.LocalDateTime;

/**
 * 单个来源的最近一次采集结果。一个源挂了要能在页面上看出来，而不是静默少了一批热点。
 *
 * @param fetched 本次入库/更新的条数
 * @param error   失败原因，成功时为 null
 */
public record HotspotSourceStatus(String code, String label, boolean available, String note,
                                  int fetched, String error, LocalDateTime lastAttemptAt) {
}
