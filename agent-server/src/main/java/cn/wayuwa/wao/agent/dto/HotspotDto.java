package cn.wayuwa.wao.agent.dto;

import java.time.LocalDateTime;

/**
 * 一条热点。
 *
 * @param heat        热度值，各来源口径不同，只在同来源内可比；拿不到时为 null
 * @param rank        榜单名次，跨来源唯一可比的信号
 * @param firstSeenAt 首次出现时间，用来看这条热点已经火了多久
 */
public record HotspotDto(String id, String source, String sourceLabel, String title, String summary,
                         String url, Long heat, Integer rank,
                         LocalDateTime firstSeenAt, LocalDateTime lastSeenAt) {
}
