package cn.wayuwa.wao.dto;

/** 基于真实记录生成的四段回顾；轨迹只表达条件性趋势，不是确定性预测。 */
public record LifeReviewReportDto(
        String pastActions,
        String meaningfulTopics,
        String trajectory,
        String nextFocus) { }
