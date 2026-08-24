package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 一次不可变的记忆文档修订。 */
@TableName("memory_revision")
public class MemoryRevisionEntity {
    @TableId private String id;
    private String memoryId;
    private Integer versionNo;
    private String state;
    private String title;
    private String contentMd;
    private String changeSummary;
    private String contentFingerprint;
    private String baseRevisionId;
    private String evidenceLinksJson;
    private String authorType;
    private String createdBy;
    private LocalDateTime createdAt;
    private String publishedBy;
    private LocalDateTime publishedAt;
    private LocalDateTime expiresAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMemoryId() { return memoryId; }
    public void setMemoryId(String memoryId) { this.memoryId = memoryId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContentMd() { return contentMd; }
    public void setContentMd(String contentMd) { this.contentMd = contentMd; }
    public String getChangeSummary() { return changeSummary; }
    public void setChangeSummary(String changeSummary) { this.changeSummary = changeSummary; }
    public String getContentFingerprint() { return contentFingerprint; }
    public void setContentFingerprint(String contentFingerprint) { this.contentFingerprint = contentFingerprint; }
    public String getBaseRevisionId() { return baseRevisionId; }
    public void setBaseRevisionId(String baseRevisionId) { this.baseRevisionId = baseRevisionId; }
    public String getEvidenceLinksJson() { return evidenceLinksJson; }
    public void setEvidenceLinksJson(String evidenceLinksJson) { this.evidenceLinksJson = evidenceLinksJson; }
    public String getAuthorType() { return authorType; }
    public void setAuthorType(String authorType) { this.authorType = authorType; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getPublishedBy() { return publishedBy; }
    public void setPublishedBy(String publishedBy) { this.publishedBy = publishedBy; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
