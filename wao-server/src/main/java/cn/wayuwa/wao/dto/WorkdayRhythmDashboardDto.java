package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.util.List;

/** 今日节奏每日面板的一次完整读取结果。 */
public record WorkdayRhythmDashboardDto(LocalDate date, boolean workday, Boolean workdayOverride,
        String workWeekLabel, List<Occurrence> checklist, int completedCount, int totalCount,
        Document document, Settings settings) {
    public record Occurrence(String id, String itemId, LocalDate occurrenceDate, String kind,
            String category, String title, String itemType, Integer durationSeconds,
            Integer timerRemainingSeconds, boolean timerRunning, String startTime, String dueTime,
            boolean dueNextDay, String completedAt, Boolean completedOnTime,
            String completionSource, String status) { }
    public record Document(String id, String state, String kbId, String folderId, String title) { }
    public record Settings(boolean followAlternateWeek, String documentKbId, String documentFolderId,
            String documentTemplateId, String documentTitlePattern, boolean tianrenEnabled,
            boolean tianrenRequired) { }
}
