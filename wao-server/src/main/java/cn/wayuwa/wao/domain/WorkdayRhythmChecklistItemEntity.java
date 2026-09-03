package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 一条可维护的每日必做模板。 */
@TableName("workday_rhythm_checklist_item")
public class WorkdayRhythmChecklistItemEntity {
    @TableId private String id;
    private String memberId;
    private String kind;
    private String systemKey;
    private String category;
    private String title;
    private String itemType;
    private Integer durationSeconds;
    private String scheduleType;
    private String weekdaysJson;
    private LocalTime startTime;
    private LocalTime dueTime;
    private Boolean dueNextDay;
    private Boolean active;
    private Integer ord;
    private Boolean notifyDirect;
    private Boolean notifyChat;
    private String chatId;
    private String chatName;
    private Boolean notifyWebhook;
    private String webhookId;
    private String webhookName;
    private LocalTime reminderTime;
    private Boolean reminderNextDay;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; } public void setId(String v) { id = v; }
    public String getMemberId() { return memberId; } public void setMemberId(String v) { memberId = v; }
    public String getKind() { return kind; } public void setKind(String v) { kind = v; }
    public String getSystemKey() { return systemKey; } public void setSystemKey(String v) { systemKey = v; }
    public String getCategory() { return category; } public void setCategory(String v) { category = v; }
    public String getTitle() { return title; } public void setTitle(String v) { title = v; }
    public String getItemType() { return itemType; } public void setItemType(String v) { itemType = v; }
    public Integer getDurationSeconds() { return durationSeconds; } public void setDurationSeconds(Integer v) { durationSeconds = v; }
    public String getScheduleType() { return scheduleType; } public void setScheduleType(String v) { scheduleType = v; }
    public String getWeekdaysJson() { return weekdaysJson; } public void setWeekdaysJson(String v) { weekdaysJson = v; }
    public LocalTime getStartTime() { return startTime; } public void setStartTime(LocalTime v) { startTime = v; }
    public LocalTime getDueTime() { return dueTime; } public void setDueTime(LocalTime v) { dueTime = v; }
    public Boolean getDueNextDay() { return dueNextDay; } public void setDueNextDay(Boolean v) { dueNextDay = v; }
    public Boolean getActive() { return active; } public void setActive(Boolean v) { active = v; }
    public Integer getOrd() { return ord; } public void setOrd(Integer v) { ord = v; }
    public Boolean getNotifyDirect() { return notifyDirect; } public void setNotifyDirect(Boolean v) { notifyDirect = v; }
    public Boolean getNotifyChat() { return notifyChat; } public void setNotifyChat(Boolean v) { notifyChat = v; }
    public String getChatId() { return chatId; } public void setChatId(String v) { chatId = v; }
    public String getChatName() { return chatName; } public void setChatName(String v) { chatName = v; }
    public Boolean getNotifyWebhook() { return notifyWebhook; } public void setNotifyWebhook(Boolean v) { notifyWebhook = v; }
    public String getWebhookId() { return webhookId; } public void setWebhookId(String v) { webhookId = v; }
    public String getWebhookName() { return webhookName; } public void setWebhookName(String v) { webhookName = v; }
    public LocalTime getReminderTime() { return reminderTime; } public void setReminderTime(LocalTime v) { reminderTime = v; }
    public Boolean getReminderNextDay() { return reminderNextDay; } public void setReminderNextDay(Boolean v) { reminderNextDay = v; }
    public LocalDateTime getDeletedAt() { return deletedAt; } public void setDeletedAt(LocalDateTime v) { deletedAt = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime v) { createdAt = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(LocalDateTime v) { updatedAt = v; }
}
