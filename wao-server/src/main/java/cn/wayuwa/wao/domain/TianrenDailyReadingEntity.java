package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 成员某一天唯一的“天人一卦”服务端存档。 */
@TableName("tianren_daily_reading")
public class TianrenDailyReadingEntity {

    @TableId
    private String id;
    private String memberId;
    private LocalDate readingDate;
    private String linesJson;
    private Integer primaryNumber;
    private Integer relatingNumber;
    private String conversationId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public LocalDate getReadingDate() { return readingDate; }
    public void setReadingDate(LocalDate readingDate) { this.readingDate = readingDate; }
    public String getLinesJson() { return linesJson; }
    public void setLinesJson(String linesJson) { this.linesJson = linesJson; }
    public Integer getPrimaryNumber() { return primaryNumber; }
    public void setPrimaryNumber(Integer primaryNumber) { this.primaryNumber = primaryNumber; }
    public Integer getRelatingNumber() { return relatingNumber; }
    public void setRelatingNumber(Integer relatingNumber) { this.relatingNumber = relatingNumber; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
