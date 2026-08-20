package cn.wayuwa.wao.dto;

/** 今日节奏某个精力补给项目在一天内的真实执行次数。 */
public record WorkdayRhythmHistoryEnergyDto(String id, String title, String mode, int count) { }
