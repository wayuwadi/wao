package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 一天的工作状态覆盖与每日文档绑定。 */
@TableName("workday_rhythm_day")
public class WorkdayRhythmDayEntity {
    private String memberId; private LocalDate rhythmDate; private Boolean workdayOverride;
    private String documentId; private LocalDateTime createdAt; private LocalDateTime updatedAt;
    public String getMemberId(){return memberId;} public void setMemberId(String v){memberId=v;}
    public LocalDate getRhythmDate(){return rhythmDate;} public void setRhythmDate(LocalDate v){rhythmDate=v;}
    public Boolean getWorkdayOverride(){return workdayOverride;} public void setWorkdayOverride(Boolean v){workdayOverride=v;}
    public String getDocumentId(){return documentId;} public void setDocumentId(String v){documentId=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
