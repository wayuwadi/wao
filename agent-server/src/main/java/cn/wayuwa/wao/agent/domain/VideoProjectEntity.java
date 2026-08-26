package cn.wayuwa.wao.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("agent_video_project")
public class VideoProjectEntity {
    @TableId
    private String id;
    private String workspaceId;
    private String title;
    private String scenesJson;
    private String status;
    private String outputMediaAssetId;
    private int version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime exportedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getScenesJson() { return scenesJson; }
    public void setScenesJson(String scenesJson) { this.scenesJson = scenesJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOutputMediaAssetId() { return outputMediaAssetId; }
    public void setOutputMediaAssetId(String outputMediaAssetId) { this.outputMediaAssetId = outputMediaAssetId; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getExportedAt() { return exportedAt; }
    public void setExportedAt(LocalDateTime exportedAt) { this.exportedAt = exportedAt; }
}
