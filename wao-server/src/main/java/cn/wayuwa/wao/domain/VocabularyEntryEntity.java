package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 归属到单个成员的英语单词或短语记录。 */
@TableName("vocabulary_entry")
public class VocabularyEntryEntity {
    @TableId private String id;
    private String ownerMemberId;
    private String term;
    private String normalizedTerm;
    private String meaningText;
    private String contextText;
    private String learningState;
    private Integer encounterCount;
    private Integer reviewCount;
    private LocalDateTime lastEncounteredAt;
    private LocalDateTime lastReviewedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(String value) { ownerMemberId = value; }
    public String getTerm() { return term; }
    public void setTerm(String value) { term = value; }
    public String getNormalizedTerm() { return normalizedTerm; }
    public void setNormalizedTerm(String value) { normalizedTerm = value; }
    public String getMeaningText() { return meaningText; }
    public void setMeaningText(String value) { meaningText = value; }
    public String getContextText() { return contextText; }
    public void setContextText(String value) { contextText = value; }
    public String getLearningState() { return learningState; }
    public void setLearningState(String value) { learningState = value; }
    public Integer getEncounterCount() { return encounterCount; }
    public void setEncounterCount(Integer value) { encounterCount = value; }
    public Integer getReviewCount() { return reviewCount; }
    public void setReviewCount(Integer value) { reviewCount = value; }
    public LocalDateTime getLastEncounteredAt() { return lastEncounteredAt; }
    public void setLastEncounteredAt(LocalDateTime value) { lastEncounteredAt = value; }
    public LocalDateTime getLastReviewedAt() { return lastReviewedAt; }
    public void setLastReviewedAt(LocalDateTime value) { lastReviewedAt = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
