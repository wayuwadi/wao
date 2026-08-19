package cn.wayuwa.wao.dto;

/** 聊天复盘的固定四段报告。 */
public record ChatReviewReportDto(
        String discussionProcess,
        String discussionDirections,
        String conclusions,
        String recommendations) { }
