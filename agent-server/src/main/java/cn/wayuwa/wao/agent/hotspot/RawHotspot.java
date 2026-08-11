package cn.wayuwa.wao.agent.hotspot;

/**
 * 采集到的一条原始热点，尚未落库。
 *
 * @param externalId 来源侧稳定标识，用于去重；来源没有 id 时用标题
 * @param title      热点标题
 * @param summary    摘要，可为空
 * @param url        原文链接，可为空
 * @param heat       热度值，各来源口径不同，只在同来源内可比；拿不到时为 null
 * @param rank       榜单名次，从 1 开始。所有来源都有，是跨来源唯一可比的信号
 */
public record RawHotspot(String externalId, String title, String summary, String url, Long heat, int rank) {
}
