package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** “天人一卦”公开只读投影；不包含成员、存档 id 与私有 AI 会话。 */
public record SharedTianrenReadingDto(
        LocalDate date,
        List<Integer> lines,
        List<Integer> changingLines,
        TianrenHexagramDto primary,
        TianrenHexagramDto relating,
        LocalDateTime createdAt) {
}
