package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.util.List;

/** 更新日记按有真实交付的日期分页；没有更新的日期不会生成空组。 */
public record UpdateDiaryPageDto(List<Day> days, long totalDays, int page, int size) {
    public record Day(LocalDate date, List<Entry> entries) { }
    public record Entry(String scope, String kind, String title) { }
}
