package cn.wayuwa.wao.dto;

import java.time.LocalDate;

/** 报告的一条每日聊天归档来源，不复制聊天正文。 */
public record ChatReviewSourceDto(
        String id,
        LocalDate date,
        String kbId,
        String documentId,
        String url,
        int messageCount) { }
