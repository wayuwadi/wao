package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.util.List;

/** 英语训练首页所需的成员设置、当日记录与真实累计数据。 */
public record EnglishLearningOverviewDto(
        EnglishLearningProfileDto profile,
        LocalDate selectedDate,
        EnglishLearningSessionDto session,
        int completedDays,
        int currentStreak,
        int totalMinutes,
        List<EnglishLearningSessionDto> recentSessions) { }
