package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 一个周期的真实资料覆盖与已生成回顾。 */
public record LifeReviewDto(
        String period,
        String periodKey,
        String periodLabel,
        LocalDate startDate,
        LocalDate endDate,
        int taskCount,
        int assistantConversationCount,
        int chatDayCount,
        List<LifeReviewSourceDto> sources,
        LifeReviewReportDto report,
        LocalDateTime generatedAt,
        String coverageMessage) { }
