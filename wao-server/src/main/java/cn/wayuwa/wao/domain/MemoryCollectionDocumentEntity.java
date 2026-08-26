package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 一台 Runner 对一个机器或应用范围的稳定采集文档映射。 */
@TableName("memory_collection_document")
public class MemoryCollectionDocumentEntity {
    @TableId private String id;
    private String runnerId;
    private String scopeType;
    private String scopeId;
    private String memoryId;
    private String contentFingerprint;
    private LocalDateTime lastCollectedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRunnerId() { return runnerId; }
    public void setRunnerId(String runnerId) { this.runnerId = runnerId; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public String getScopeId() { return scopeId; }
    public void setScopeId(String scopeId) { this.scopeId = scopeId; }
    public String getMemoryId() { return memoryId; }
    public void setMemoryId(String memoryId) { this.memoryId = memoryId; }
    public String getContentFingerprint() { return contentFingerprint; }
    public void setContentFingerprint(String value) { this.contentFingerprint = value; }
    public LocalDateTime getLastCollectedAt() { return lastCollectedAt; }
    public void setLastCollectedAt(LocalDateTime value) { this.lastCollectedAt = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { this.createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { this.updatedAt = value; }
}

