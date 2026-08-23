package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 成员的今日节奏面板与每日文档设置。 */
@TableName("workday_rhythm_setting")
public class WorkdayRhythmSettingEntity {
    @TableId private String memberId;
    private Boolean followAlternateWeek;
    private String documentKbId;
    private String documentFolderId;
    private String documentTemplateId;
    private String documentTitlePattern;
    private Boolean tianrenEnabled;
    private Boolean tianrenRequired;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getMemberId() { return memberId; }
    public void setMemberId(String value) { memberId = value; }
    public Boolean getFollowAlternateWeek() { return followAlternateWeek; }
    public void setFollowAlternateWeek(Boolean value) { followAlternateWeek = value; }
    public String getDocumentKbId() { return documentKbId; }
    public void setDocumentKbId(String value) { documentKbId = value; }
    public String getDocumentFolderId() { return documentFolderId; }
    public void setDocumentFolderId(String value) { documentFolderId = value; }
    public String getDocumentTemplateId() { return documentTemplateId; }
    public void setDocumentTemplateId(String value) { documentTemplateId = value; }
    public String getDocumentTitlePattern() { return documentTitlePattern; }
    public void setDocumentTitlePattern(String value) { documentTitlePattern = value; }
    public Boolean getTianrenEnabled() { return tianrenEnabled; }
    public void setTianrenEnabled(Boolean value) { tianrenEnabled = value; }
    public Boolean getTianrenRequired() { return tianrenRequired; }
    public void setTianrenRequired(Boolean value) { tianrenRequired = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
