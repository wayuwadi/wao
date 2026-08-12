package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** 行动计划详情与真实打卡投影。 */
public record ActionPlanDto(
        String id,
        String title,
        String vision,
        LocalDate startDate,
        LocalDate targetDate,
        LocalTime reminderTime,
        boolean reminderEnabled,
        String status,
        LocalDate selectedDate,
        List<ActionPlanItemDto> items,
        String note,
        int scheduledCount,
        int completedCount,
        int currentStreak,
        int totalCompletedDays,
        String reminderState) { }
