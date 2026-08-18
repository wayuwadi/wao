package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 当前成员维护的一个按公历月日每年重复的家庭重要日。 */
@TableName("family_important_date")
public class FamilyImportantDateEntity {
    @TableId private String id;
    private String memberId;
    private String name;
    private LocalDate eventDate;
    private String note;
    private Boolean reminderEnabled;
    private Integer reminderDaysBefore;
    private LocalTime reminderTime;
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
    public String getNote() { return note; }
    public void setNote(String value) { note = value; }
    public Boolean getReminderEnabled() { return reminderEnabled; }
    public void setReminderEnabled(Boolean value) { reminderEnabled = value; }
    public Integer getReminderDaysBefore() { return reminderDaysBefore; }
    public void setReminderDaysBefore(Integer value) { reminderDaysBefore = value; }
    public LocalTime getReminderTime() { return reminderTime; }
    public void setReminderTime(LocalTime value) { reminderTime = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
