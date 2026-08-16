package cn.wayuwa.wao.dto;

import java.util.List;

/** 一页已经结束的 AI 运行，供首页历史弹窗按需翻页。 */
public record AiRunPageDto(List<AiRunDto> items, long total, int page, int size) {
}
