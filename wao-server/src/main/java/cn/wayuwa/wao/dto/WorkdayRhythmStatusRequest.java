package cn.wayuwa.wao.dto;
/** active 为 null 时清除当天覆盖，恢复自动工作日判断。 */
public record WorkdayRhythmStatusRequest(Boolean active) { }
