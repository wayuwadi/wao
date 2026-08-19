package cn.wayuwa.wao.dto;

import java.util.List;

/** 共享聊天复盘专页首屏所需的活动运行与终态历史。 */
public record ChatReviewListDto(
        ChatReviewRunSummaryDto activeRun,
        List<ChatReviewRunSummaryDto> history) { }
