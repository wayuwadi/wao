package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 市场 Skill 的稳定身份与当前对外版本指针。 */
@TableName("skill")
public class SkillEntity {
    @TableId private String id;
    private String publisherId;
    private String slug;
    private String displayName;
    private String summary;
    private String category;
    private String tagsJson;
    private String status;
    private String publishedVersionId;
    private Long downloadCount;
    private Long favoriteCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    public String getId() { return id; } public void setId(String v) { id = v; }
    public String getPublisherId() { return publisherId; } public void setPublisherId(String v) { publisherId = v; }
    public String getSlug() { return slug; } public void setSlug(String v) { slug = v; }
    public String getDisplayName() { return displayName; } public void setDisplayName(String v) { displayName = v; }
    public String getSummary() { return summary; } public void setSummary(String v) { summary = v; }
    public String getCategory() { return category; } public void setCategory(String v) { category = v; }
    public String getTagsJson() { return tagsJson; } public void setTagsJson(String v) { tagsJson = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public String getPublishedVersionId() { return publishedVersionId; } public void setPublishedVersionId(String v) { publishedVersionId = v; }
    public Long getDownloadCount() { return downloadCount; } public void setDownloadCount(Long v) { downloadCount = v; }
    public Long getFavoriteCount() { return favoriteCount; } public void setFavoriteCount(Long v) { favoriteCount = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime v) { createdAt = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(LocalDateTime v) { updatedAt = v; }
}
