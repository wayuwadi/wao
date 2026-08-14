package cn.wayuwa.wao.dto;

/** 中国法定放假调休安排在指定年份是否有可靠数据。 */
public record HolidayYearSourceDto(
        int year,
        boolean officialScheduleAvailable,
        String sourceName,
        String sourceUrl,
        String message) { }
