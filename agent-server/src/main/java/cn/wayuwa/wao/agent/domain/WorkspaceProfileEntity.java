package cn.wayuwa.wao.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("agent_workspace_profile")
public class WorkspaceProfileEntity {
    @TableId
    private String id;
    private String workspaceId;
    private String niche;
    private String audience;
    private String tone;
    private String avoid;
    private String contentTopics;
    private String lengthStructure;
    private String updateFrequency;
    private String primaryPlatforms;
    private String imageStyle;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getNiche() { return niche; }
    public void setNiche(String niche) { this.niche = niche; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public String getTone() { return tone; }
    public void setTone(String tone) { this.tone = tone; }
    public String getAvoid() { return avoid; }
    public void setAvoid(String avoid) { this.avoid = avoid; }
    public String getContentTopics() { return contentTopics; }
    public void setContentTopics(String contentTopics) { this.contentTopics = contentTopics; }
    public String getLengthStructure() { return lengthStructure; }
    public void setLengthStructure(String lengthStructure) { this.lengthStructure = lengthStructure; }
    public String getUpdateFrequency() { return updateFrequency; }
    public void setUpdateFrequency(String updateFrequency) { this.updateFrequency = updateFrequency; }
    public String getPrimaryPlatforms() { return primaryPlatforms; }
    public void setPrimaryPlatforms(String primaryPlatforms) { this.primaryPlatforms = primaryPlatforms; }
    public String getImageStyle() { return imageStyle; }
    public void setImageStyle(String imageStyle) { this.imageStyle = imageStyle; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
