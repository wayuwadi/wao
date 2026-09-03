package cn.wayuwa.wao.dto;
import java.util.List;
/** 可维护的每日必做模板。 */
public record WorkdayRhythmChecklistItemDto(String id, String kind, String category, String title,
        String itemType, Integer durationMinutes,
        String scheduleType, List<Integer> weekdays, String startTime, String dueTime,
        boolean dueNextDay, boolean active, int order, boolean notifyDirect, boolean notifyChat,
        String chatId, String chatName, boolean notifyWebhook, String webhookId, String webhookName,
        String reminderTime, boolean reminderNextDay) { }
