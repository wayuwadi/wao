package cn.wayuwa.wao.dto;
import java.util.List;
/** 保存今日三个重点，三项均非空时联动完成计划项。 */
public record SaveWorkdayRhythmFocusRequest(List<String> focus) { }
