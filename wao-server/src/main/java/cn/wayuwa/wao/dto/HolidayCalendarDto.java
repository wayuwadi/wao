package cn.wayuwa.wao.dto;

import java.util.List;

/** 一个日期窗内的节日事件及涉及年份的法定安排覆盖状态。 */
public record HolidayCalendarDto(List<HolidayEventDto> events, List<HolidayYearSourceDto> years) { }
