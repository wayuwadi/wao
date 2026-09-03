package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 按日期固化的清单实例，用于历史、跨午夜和通知去重。 */
@TableName("workday_rhythm_checklist_occurrence")
public class WorkdayRhythmChecklistOccurrenceEntity {
    @TableId private String id;
    private String itemId; private String memberId; private LocalDate occurrenceDate;
    private String kind; private String category; private String title;
    private String itemType; private Integer durationSeconds;
    private LocalDateTime timerStartedAt; private Integer timerElapsedSeconds;
    private LocalTime startTime; private LocalTime dueTime; private Boolean dueNextDay;
    private LocalTime reminderTime; private Boolean reminderNextDay;
    private Boolean notifyDirect; private Boolean notifyChat; private String chatId; private String chatName;
    private Boolean notifyWebhook; private String webhookId; private String webhookName;
    private LocalDateTime completedAt; private Boolean completedOnTime; private String completionSource;
    private LocalDateTime createdAt; private LocalDateTime updatedAt;

    public String getId(){return id;} public void setId(String v){id=v;}
    public String getItemId(){return itemId;} public void setItemId(String v){itemId=v;}
    public String getMemberId(){return memberId;} public void setMemberId(String v){memberId=v;}
    public LocalDate getOccurrenceDate(){return occurrenceDate;} public void setOccurrenceDate(LocalDate v){occurrenceDate=v;}
    public String getKind(){return kind;} public void setKind(String v){kind=v;}
    public String getCategory(){return category;} public void setCategory(String v){category=v;}
    public String getTitle(){return title;} public void setTitle(String v){title=v;}
    public String getItemType(){return itemType;} public void setItemType(String v){itemType=v;}
    public Integer getDurationSeconds(){return durationSeconds;} public void setDurationSeconds(Integer v){durationSeconds=v;}
    public LocalDateTime getTimerStartedAt(){return timerStartedAt;} public void setTimerStartedAt(LocalDateTime v){timerStartedAt=v;}
    public Integer getTimerElapsedSeconds(){return timerElapsedSeconds;} public void setTimerElapsedSeconds(Integer v){timerElapsedSeconds=v;}
    public LocalTime getStartTime(){return startTime;} public void setStartTime(LocalTime v){startTime=v;}
    public LocalTime getDueTime(){return dueTime;} public void setDueTime(LocalTime v){dueTime=v;}
    public Boolean getDueNextDay(){return dueNextDay;} public void setDueNextDay(Boolean v){dueNextDay=v;}
    public LocalTime getReminderTime(){return reminderTime;} public void setReminderTime(LocalTime v){reminderTime=v;}
    public Boolean getReminderNextDay(){return reminderNextDay;} public void setReminderNextDay(Boolean v){reminderNextDay=v;}
    public Boolean getNotifyDirect(){return notifyDirect;} public void setNotifyDirect(Boolean v){notifyDirect=v;}
    public Boolean getNotifyChat(){return notifyChat;} public void setNotifyChat(Boolean v){notifyChat=v;}
    public String getChatId(){return chatId;} public void setChatId(String v){chatId=v;}
    public String getChatName(){return chatName;} public void setChatName(String v){chatName=v;}
    public Boolean getNotifyWebhook(){return notifyWebhook;} public void setNotifyWebhook(Boolean v){notifyWebhook=v;}
    public String getWebhookId(){return webhookId;} public void setWebhookId(String v){webhookId=v;}
    public String getWebhookName(){return webhookName;} public void setWebhookName(String v){webhookName=v;}
    public LocalDateTime getCompletedAt(){return completedAt;} public void setCompletedAt(LocalDateTime v){completedAt=v;}
    public Boolean getCompletedOnTime(){return completedOnTime;} public void setCompletedOnTime(Boolean v){completedOnTime=v;}
    public String getCompletionSource(){return completionSource;} public void setCompletionSource(String v){completionSource=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
