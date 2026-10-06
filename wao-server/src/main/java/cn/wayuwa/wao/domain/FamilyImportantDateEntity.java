package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 当前成员维护的一个按公历或农历每年重复的日历提醒事项。 */
@TableName("family_important_date")
public class FamilyImportantDateEntity {
    @TableId private String id;
    private String memberId;
    private String name;
    private LocalDate eventDate;
    private String calendarType;
    private Integer lunarMonth;
    private Integer lunarDay;
    private Boolean lunarLeapMonth;
    private String note;
    private String eventKind;
    private String birthdaySubject;
    private Boolean homeReminderEnabled;
    private Boolean reminderEnabled;
    private Integer reminderDaysBefore;
    private LocalTime reminderTime;
    private String notificationType;
    private String chatId;
    private String chatName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String value) { memberId = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public LocalDate getEventDate() { return eventDate; }
    public void setEventDate(LocalDate value) { eventDate = value; }
    public String getCalendarType() { return calendarType; }
    public void setCalendarType(String value) { calendarType = value; }
    public Integer getLunarMonth() { return lunarMonth; }
    public void setLunarMonth(Integer value) { lunarMonth = value; }
    public Integer getLunarDay() { return lunarDay; }
    public void setLunarDay(Integer value) { lunarDay = value; }
    public Boolean getLunarLeapMonth() { return lunarLeapMonth; }
    public void setLunarLeapMonth(Boolean value) { lunarLeapMonth = value; }
    public String getNote() { return note; }
    public String getEventKind() { return eventKind; }
    public void setEventKind(String value) { eventKind = value; }
    public String getBirthdaySubject() { return birthdaySubject; }
    public void setBirthdaySubject(String value) { birthdaySubject = value; }
    public Boolean getHomeReminderEnabled() { return homeReminderEnabled; }
    public void setHomeReminderEnabled(Boolean value) { homeReminderEnabled = value; }
    public void setNote(String value) { note = value; }
    public Boolean getReminderEnabled() { return reminderEnabled; }
    public void setReminderEnabled(Boolean value) { reminderEnabled = value; }
    public Integer getReminderDaysBefore() { return reminderDaysBefore; }
    public void setReminderDaysBefore(Integer value) { reminderDaysBefore = value; }
    public LocalTime getReminderTime() { return reminderTime; }
    public void setReminderTime(LocalTime value) { reminderTime = value; }
    public String getNotificationType() { return notificationType; }
    public void setNotificationType(String value) { notificationType = value; }
    public String getChatId() { return chatId; }
    public void setChatId(String value) { chatId = value; }
    public String getChatName() { return chatName; }
    public void setChatName(String value) { chatName = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
