package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 一次可跨设备恢复的每日卦象；爻值按初爻到上爻排列。 */
public record TianrenReadingDto(
        String id,
        LocalDate date,
        List<Integer> lines,
        List<Integer> changingLines,
        TianrenHexagramDto primary,
        TianrenHexagramDto relating,
        String conversationId,
        LocalDateTime createdAt) {
}
