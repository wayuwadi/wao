package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 一次清单提醒的通道级投递记录。 */
@TableName("workday_rhythm_reminder_delivery")
public class WorkdayRhythmReminderDeliveryEntity {
    @TableId private String id; private String occurrenceId; private String channel; private String status;
    private Integer attempts; private String errorMessage; private LocalDateTime attemptedAt; private LocalDateTime sentAt;
    public String getId(){return id;} public void setId(String v){id=v;}
    public String getOccurrenceId(){return occurrenceId;} public void setOccurrenceId(String v){occurrenceId=v;}
    public String getChannel(){return channel;} public void setChannel(String v){channel=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public Integer getAttempts(){return attempts;} public void setAttempts(Integer v){attempts=v;}
    public String getErrorMessage(){return errorMessage;} public void setErrorMessage(String v){errorMessage=v;}
    public LocalDateTime getAttemptedAt(){return attemptedAt;} public void setAttemptedAt(LocalDateTime v){attemptedAt=v;}
    public LocalDateTime getSentAt(){return sentAt;} public void setSentAt(LocalDateTime v){sentAt=v;}
}
