package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.util.List;

/** 当前成员指定日期范围内的今日节奏真实存档。 */
public record WorkdayRhythmHistoryDto(LocalDate from, LocalDate to,
        List<WorkdayRhythmHistoryDayDto> items, int skippedCount) { }
