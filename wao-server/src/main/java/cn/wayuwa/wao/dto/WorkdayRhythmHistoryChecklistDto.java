package cn.wayuwa.wao.dto;
/** 历史页展示的一条必做快照。 */
public record WorkdayRhythmHistoryChecklistDto(String title, String category, String completedAt,
        Boolean completedOnTime) { }
