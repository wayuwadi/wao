package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 回顾历史列表中的轻量条目。 */
public record LifeReviewHistoryDto(
        String period,
        String periodKey,
        String periodLabel,
        LocalDate startDate,
        LocalDate endDate,
        LocalDateTime generatedAt,
        String excerpt) { }
