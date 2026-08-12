package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 当前成员的英语训练目标与每日投入。 */
public record EnglishLearningProfileDto(
        String id,
        String goal,
        int dailyTargetMinutes,
        LocalDate startDate,
        LocalDateTime updatedAt) { }
