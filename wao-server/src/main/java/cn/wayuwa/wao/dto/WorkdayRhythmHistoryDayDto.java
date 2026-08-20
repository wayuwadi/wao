package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.util.List;

/** 当前成员一天的今日节奏存档摘要。 */
public record WorkdayRhythmHistoryDayDto(LocalDate date, boolean workdayActive,
        List<String> focus, List<WorkdayRhythmHistoryEnergyDto> energyItems) { }
