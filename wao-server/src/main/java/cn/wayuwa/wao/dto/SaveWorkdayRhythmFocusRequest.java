package cn.wayuwa.wao.dto;
import java.util.List;
/** 保存今日重点，默认三项并允许继续追加。 */
public record SaveWorkdayRhythmFocusRequest(List<String> focus) { }
