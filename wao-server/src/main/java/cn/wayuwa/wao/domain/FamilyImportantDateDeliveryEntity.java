package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 家庭重要日的一次真实飞书投递，唯一键保证多节点不会重复发送。 */
@TableName("family_important_date_delivery")
public class FamilyImportantDateDeliveryEntity {
    @TableId private String id;
    private String importantDateId;
    private LocalDate occurrenceDate;
    private String status;
    private Integer attempts;
    private String errorMessage;
    private LocalDateTime attemptedAt;
    private LocalDateTime sentAt;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getImportantDateId() { return importantDateId; }
    public void setImportantDateId(String value) { importantDateId = value; }
    public LocalDate getOccurrenceDate() { return occurrenceDate; }
    public void setOccurrenceDate(LocalDate value) { occurrenceDate = value; }
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
