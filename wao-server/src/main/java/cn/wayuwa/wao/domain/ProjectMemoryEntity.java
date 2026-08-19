package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 项目成员共享的工程记忆；与成员私有的个人记忆严格分表。 */
@TableName("project_memory")
public class ProjectMemoryEntity {
    @TableId
    private String id;
    private String projectId;
    private String category;
    private String title;
    private String content;
    private String sourceTaskId;
    private String sourceRunId;
    private String evidenceLinksJson;
    private String contentFingerprint;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSourceTaskId() { return sourceTaskId; }
    public void setSourceTaskId(String sourceTaskId) { this.sourceTaskId = sourceTaskId; }
    public String getSourceRunId() { return sourceRunId; }
    public void setSourceRunId(String sourceRunId) { this.sourceRunId = sourceRunId; }
    public String getEvidenceLinksJson() { return evidenceLinksJson; }
    public void setEvidenceLinksJson(String evidenceLinksJson) { this.evidenceLinksJson = evidenceLinksJson; }
    public String getContentFingerprint() { return contentFingerprint; }
    public void setContentFingerprint(String contentFingerprint) { this.contentFingerprint = contentFingerprint; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
