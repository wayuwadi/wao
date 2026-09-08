package cn.wayuwa.wao.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("agent_publish_task")
public class PublishTaskEntity {
    @TableId
    private String id;
    private String workspaceId;
    private String requestedByAccountId;
    private String contentGroupId;
    private String platform;
    private String platformAccountId;
    private String mediaAssetId;
    private String topic;
    private String title;
    private String content;
    private String tagsJson;
    private String status;
    private int progress;
    private String nodeId;
    private String platformItemId;
    private String errorMessage;
    private int version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime publishedAt;
    private String structureJson;
    private String coverText;
    private String sourceHotspotId;
    private String sourceNotes;
    private Boolean useWaoContext;
    private String sourceContextJson;
    private String generationExecutor;
    private String generationSystemPrompt;
    private String generationUserPrompt;
    private String generationClaimToken;
    private LocalDateTime generationLeaseUntil;
    private String publicationUrl;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getRequestedByAccountId() { return requestedByAccountId; }
    public void setRequestedByAccountId(String requestedByAccountId) { this.requestedByAccountId = requestedByAccountId; }
    public String getContentGroupId() { return contentGroupId; }
    public void setContentGroupId(String value) { contentGroupId = value; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getPlatformAccountId() { return platformAccountId; }
    public void setPlatformAccountId(String platformAccountId) { this.platformAccountId = platformAccountId; }
    public String getMediaAssetId() { return mediaAssetId; }
    public void setMediaAssetId(String mediaAssetId) { this.mediaAssetId = mediaAssetId; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getTagsJson() { return tagsJson; }
    public void setTagsJson(String tagsJson) { this.tagsJson = tagsJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getPlatformItemId() { return platformItemId; }
    public void setPlatformItemId(String platformItemId) { this.platformItemId = platformItemId; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public String getStructureJson() { return structureJson; }
    public void setStructureJson(String structureJson) { this.structureJson = structureJson; }
    public String getCoverText() { return coverText; }
    public void setCoverText(String coverText) { this.coverText = coverText; }
    public String getSourceHotspotId() { return sourceHotspotId; }
    public void setSourceHotspotId(String sourceHotspotId) { this.sourceHotspotId = sourceHotspotId; }
    public String getSourceNotes() { return sourceNotes; }
    public void setSourceNotes(String value) { sourceNotes = value; }
    public Boolean getUseWaoContext() { return useWaoContext; }
    public void setUseWaoContext(Boolean value) { useWaoContext = value; }
    public String getSourceContextJson() { return sourceContextJson; }
    public void setSourceContextJson(String value) { sourceContextJson = value; }
    public String getGenerationExecutor() { return generationExecutor; }
    public void setGenerationExecutor(String value) { generationExecutor = value; }
    public String getGenerationSystemPrompt() { return generationSystemPrompt; }
    public void setGenerationSystemPrompt(String value) { generationSystemPrompt = value; }
    public String getGenerationUserPrompt() { return generationUserPrompt; }
    public void setGenerationUserPrompt(String value) { generationUserPrompt = value; }
    public String getGenerationClaimToken() { return generationClaimToken; }
    public void setGenerationClaimToken(String value) { generationClaimToken = value; }
    public LocalDateTime getGenerationLeaseUntil() { return generationLeaseUntil; }
    public void setGenerationLeaseUntil(LocalDateTime value) { generationLeaseUntil = value; }
    public String getPublicationUrl() { return publicationUrl; }
    public void setPublicationUrl(String value) { publicationUrl = value; }
}
