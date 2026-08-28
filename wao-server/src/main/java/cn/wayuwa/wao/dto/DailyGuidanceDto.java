package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 首页给当前成员的每日成长启发；正文只保存真实 Codex 生成结果。 */
public record DailyGuidanceDto(
        LocalDate date,
        String status,
        String content,
        String message,
        LocalDateTime generatedAt) {
}
