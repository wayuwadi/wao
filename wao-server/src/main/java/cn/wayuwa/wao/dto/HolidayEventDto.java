package cn.wayuwa.wao.dto;

/**
 * 内置节假日日历事件。
 *
 * @param id       稳定事件 id
 * @param date     日期（yyyy-MM-dd）
 * @param name     中文展示名
 * @param category {@code CHINA} 或 {@code INTERNATIONAL}
 * @param dayType  {@code FESTIVAL}、{@code DAY_OFF} 或 {@code ADJUSTED_WORKDAY}
 */
public record HolidayEventDto(
        String id,
        String date,
        String name,
        String category,
        String dayType) {
}
