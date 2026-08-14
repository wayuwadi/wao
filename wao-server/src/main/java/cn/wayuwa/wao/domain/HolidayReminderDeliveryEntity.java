package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 一次节日提醒的实际投递状态，用唯一键保证多节点不重复私聊。 */
@TableName("holiday_reminder_delivery")
public class HolidayReminderDeliveryEntity {
    @TableId private String id;
    private String reminderId;
    private LocalDate eventDate;
    private String status;
    private Integer attempts;
    private String errorMessage;
    private LocalDateTime attemptedAt;
    private LocalDateTime sentAt;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getReminderId() { return reminderId; }
    public void setReminderId(String value) { reminderId = value; }
    public LocalDate getEventDate() { return eventDate; }
    public void setEventDate(LocalDate value) { eventDate = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer value) { attempts = value; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String value) { errorMessage = value; }
    public LocalDateTime getAttemptedAt() { return attemptedAt; }
    public void setAttemptedAt(LocalDateTime value) { attemptedAt = value; }
    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime value) { sentAt = value; }
}
