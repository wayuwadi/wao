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
    private String contentHash;
    private LocalDateTime createdAt;
    private byte[] content;

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
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }
}
