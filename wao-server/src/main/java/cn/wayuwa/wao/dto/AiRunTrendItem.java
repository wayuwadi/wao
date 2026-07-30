package cn.wayuwa.wao.dto;

/** 某一天的 AI 运行统计。 */
public record AiRunTrendItem(String date, int total, int succeeded, int failed, int cancelled) {
}
