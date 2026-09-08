package cn.wayuwa.wao.dto;

import java.util.List;

/** 对话历史按稳定游标分页；items 按时间正序展示。 */
public record AiRunProgressPageDto(List<AiRunProgressDto> items, String nextCursor) { }
