package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 准则当前版本；历史版本由 revision 表保存。 */
@TableName("personal_principle")
public class PersonalPrincipleEntity {
    @TableId private String id;
    private String ownerMemberId;
    private String title;
    private String category;
    private String triggerText;
    private String desiredAction;
    private String exceptionText;
    private LocalTime reminderTime;
    private String evidenceSource;
    private Boolean paused;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(String value) { ownerMemberId = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getCategory() { return category; }
    public void setCategory(String value) { category = value; }
    public String getTriggerText() { return triggerText; }
    public void setTriggerText(String value) { triggerText = value; }
    public String getDesiredAction() { return desiredAction; }
    public void setDesiredAction(String value) { desiredAction = value; }
    public String getExceptionText() { return exceptionText; }
    public void setExceptionText(String value) { exceptionText = value; }
    public LocalTime getReminderTime() { return reminderTime; }
    public void setReminderTime(LocalTime value) { reminderTime = value; }
    public String getEvidenceSource() { return evidenceSource; }
    public void setEvidenceSource(String value) { evidenceSource = value; }
    public Boolean getPaused() { return paused; }
    public void setPaused(Boolean value) { paused = value; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { version = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
