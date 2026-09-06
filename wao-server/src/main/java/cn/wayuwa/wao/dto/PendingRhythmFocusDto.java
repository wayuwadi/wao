package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.util.List;

/** 历史未完成重点及未能解析的存档数量，不复制或改写历史计划。 */
public record PendingRhythmFocusDto(LocalDate date, List<Item> items, int skippedCount) {
    public record Item(LocalDate date, int index, String title) {}
}
