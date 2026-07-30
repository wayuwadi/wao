package cn.wayuwa.wao.dto;

/** 某一天的任务完成 / 创建统计。 */
public record TaskTrendItem(String date, int completed, int created) {
}
