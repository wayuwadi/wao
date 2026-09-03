package cn.wayuwa.wao.dto;
import java.util.List;
/** 新增或修改一条每日必做模板。 */
public record SaveWorkdayRhythmChecklistItemRequest(String category, String title, String itemType,
        Integer durationMinutes, String scheduleType,
        List<Integer> weekdays, String startTime, String dueTime, boolean dueNextDay, boolean active,
        int order, boolean notifyDirect, boolean notifyChat, String chatId, String chatName,
        boolean notifyWebhook, String webhookId, String webhookName, String reminderTime,
        boolean reminderNextDay) { }
