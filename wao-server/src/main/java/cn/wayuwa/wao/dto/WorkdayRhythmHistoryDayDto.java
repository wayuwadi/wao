package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.util.List;

/** 当前成员一天的今日节奏存档摘要。 */
public record WorkdayRhythmHistoryDayDto(LocalDate date, Boolean workdayActive,
        List<String> focus, List<Boolean> focusCompleted, List<String> review, List<WorkdayRhythmHistoryEnergyDto> energyItems,
        List<WorkdayRhythmHistoryChecklistDto> checklist, String documentId,
        String documentKbId, String documentTitle) { }
