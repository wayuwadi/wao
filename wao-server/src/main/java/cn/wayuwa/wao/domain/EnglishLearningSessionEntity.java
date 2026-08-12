package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 某位成员某天唯一的英语训练事实记录。 */
@TableName("english_learning_session")
public class EnglishLearningSessionEntity {
    @TableId private String id;
    private String memberId;
    private LocalDate studyDate;
    private String lessonId;
    private Boolean listeningDone;
    private Boolean speakingDone;
    private Boolean readingDone;
    private Boolean writingDone;
    private Integer practiceMinutes;
    private Integer confidence;
    private String writingText;
    private String reflection;
    private String nextFocus;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public LocalDate getStudyDate() { return studyDate; }
    public void setStudyDate(LocalDate studyDate) { this.studyDate = studyDate; }
    public String getLessonId() { return lessonId; }
    public void setLessonId(String lessonId) { this.lessonId = lessonId; }
    public Boolean getListeningDone() { return listeningDone; }
    public void setListeningDone(Boolean listeningDone) { this.listeningDone = listeningDone; }
    public Boolean getSpeakingDone() { return speakingDone; }
    public void setSpeakingDone(Boolean speakingDone) { this.speakingDone = speakingDone; }
    public Boolean getReadingDone() { return readingDone; }
    public void setReadingDone(Boolean readingDone) { this.readingDone = readingDone; }
    public Boolean getWritingDone() { return writingDone; }
    public void setWritingDone(Boolean writingDone) { this.writingDone = writingDone; }
    public Integer getPracticeMinutes() { return practiceMinutes; }
    public void setPracticeMinutes(Integer practiceMinutes) { this.practiceMinutes = practiceMinutes; }
    public Integer getConfidence() { return confidence; }
    public void setConfidence(Integer confidence) { this.confidence = confidence; }
    public String getWritingText() { return writingText; }
    public void setWritingText(String writingText) { this.writingText = writingText; }
    public String getReflection() { return reflection; }
    public void setReflection(String reflection) { this.reflection = reflection; }
    public String getNextFocus() { return nextFocus; }
    public void setNextFocus(String nextFocus) { this.nextFocus = nextFocus; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
