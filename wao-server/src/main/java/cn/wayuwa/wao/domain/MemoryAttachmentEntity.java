package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 绑定到不可变记忆版本的附件原件。 */
@TableName("memory_attachment")
public class MemoryAttachmentEntity {
    @TableId private String id;
    private String revisionId;
    private String fileName;
    private String contentType;
    private Long byteSize;
    private byte[] fileContent;
    private LocalDateTime createdAt;
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRevisionId() { return revisionId; }
    public void setRevisionId(String revisionId) { this.revisionId = revisionId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getByteSize() { return byteSize; }
    public void setByteSize(Long byteSize) { this.byteSize = byteSize; }
    public byte[] getFileContent() { return fileContent; }
    public void setFileContent(byte[] fileContent) { this.fileContent = fileContent; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
