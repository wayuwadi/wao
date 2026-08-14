package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 当前成员针对一个稳定节日代码设置的年度重复提醒。 */
@TableName("holiday_reminder")
public class HolidayReminderEntity {
    @TableId private String id;
    private String memberId;
    private String holidayCode;
    private String holidayName;
    private LocalTime reminderTime;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String value) { memberId = value; }
    public String getHolidayCode() { return holidayCode; }
    public void setHolidayCode(String value) { holidayCode = value; }
    public String getHolidayName() { return holidayName; }
    public void setHolidayName(String value) { holidayName = value; }
    public LocalTime getReminderTime() { return reminderTime; }
    public void setReminderTime(LocalTime value) { reminderTime = value; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean value) { enabled = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
