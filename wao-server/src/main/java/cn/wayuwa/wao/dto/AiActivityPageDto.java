package cn.wayuwa.wao.dto;

import java.util.List;

/** 首页统一 AI 活动的一页结果。 */
public record AiActivityPageDto(List<AiActivityDto> items, long total, int page, int size) { }
