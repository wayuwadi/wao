package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 团队共享点子；阶段与任务状态互相独立。 */
@TableName("idea")
public class IdeaEntity {
    @TableId private String id;
    private String title;
    private String rawIdea;
    private String problemText;
    private String targetOutcome;
    private String priority;
    private String stage;
    private String createdBy;
    private String ownerId;
    private String projectId;
    private String detailJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    public String getId() { return id; } public void setId(String v) { id = v; }
    public String getTitle() { return title; } public void setTitle(String v) { title = v; }
    public String getRawIdea() { return rawIdea; } public void setRawIdea(String v) { rawIdea = v; }
    public String getProblemText() { return problemText; } public void setProblemText(String v) { problemText = v; }
    public String getTargetOutcome() { return targetOutcome; } public void setTargetOutcome(String v) { targetOutcome = v; }
    public String getPriority() { return priority; } public void setPriority(String v) { priority = v; }
    public String getStage() { return stage; } public void setStage(String v) { stage = v; }
    public String getCreatedBy() { return createdBy; } public void setCreatedBy(String v) { createdBy = v; }
    public String getOwnerId() { return ownerId; } public void setOwnerId(String v) { ownerId = v; }
    public String getProjectId() { return projectId; } public void setProjectId(String v) { projectId = v; }
    public String getDetailJson() { return detailJson; } public void setDetailJson(String v) { detailJson = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime v) { createdAt = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(LocalDateTime v) { updatedAt = v; }
}
