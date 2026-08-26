package cn.wayuwa.wao.dto;

/** 系统每天预期断网的时间窗口；开始时间包含、结束时间不包含。 */
public record NetworkOutageWindowDto(boolean enabled, String startTime, String endTime) { }
