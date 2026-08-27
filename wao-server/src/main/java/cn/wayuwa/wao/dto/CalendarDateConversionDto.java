package cn.wayuwa.wao.dto;

/** 公历与农历互转后的权威日期，供录入表单即时展示另一种历法。 */
public record CalendarDateConversionDto(String solarDate, int lunarYear, int lunarMonth,
        int lunarDay, boolean lunarLeapMonth, String solarLabel, String lunarLabel) { }
