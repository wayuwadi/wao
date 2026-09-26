package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 图床图片实体：{@code content} 是图片二进制正文，列表查询不应带上它。 */
@TableName("image_blob")
public class ImageBlobEntity {

    @TableId
    private String id;
    private String name;
    private String contentType;
    private Long byteSize;
    private String source;
    private String projectId;
    private String contentHash;
    private String dedupeKey;
    private LocalDateTime createdAt;
    private byte[] content;
    private String objectKey;
    private String objectContentType;
    private Long objectByteSize;
    private String objectHash;
    private String previewKey;
    private String originalArchiveKey;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getByteSize() { return byteSize; }
    public void setByteSize(Long byteSize) { this.byteSize = byteSize; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String dedupeKey) { this.dedupeKey = dedupeKey; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public String getObjectContentType() { return objectContentType; }
    public void setObjectContentType(String objectContentType) { this.objectContentType = objectContentType; }
    public Long getObjectByteSize() { return objectByteSize; }
    public void setObjectByteSize(Long objectByteSize) { this.objectByteSize = objectByteSize; }
    public String getObjectHash() { return objectHash; }
    public void setObjectHash(String objectHash) { this.objectHash = objectHash; }
    public String getPreviewKey() { return previewKey; }
    public void setPreviewKey(String previewKey) { this.previewKey = previewKey; }
    public String getOriginalArchiveKey() { return originalArchiveKey; }
    public void setOriginalArchiveKey(String originalArchiveKey) { this.originalArchiveKey = originalArchiveKey; }
}
