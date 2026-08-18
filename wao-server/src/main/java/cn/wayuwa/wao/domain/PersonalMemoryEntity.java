package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 按成员隔离的个人生活记忆；文件正文默认不参与查询。 */
@TableName("personal_memory")
public class PersonalMemoryEntity {
    @TableId private String id;
    private String memberId;
    private String title;
    private String content;
    private String category;
    private String captureMode;
    private String mediaType;
    private String fileName;
    private String contentType;
    private Long byteSize;
    @TableField(select = false) private byte[] fileContent;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String value) { memberId = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
    public String getCategory() { return category; }
    public void setCategory(String value) { category = value; }
    public String getCaptureMode() { return captureMode; }
    public void setCaptureMode(String value) { captureMode = value; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String value) { mediaType = value; }
    public String getFileName() { return fileName; }
    public void setFileName(String value) { fileName = value; }
    public String getContentType() { return contentType; }
    public void setContentType(String value) { contentType = value; }
    public Long getByteSize() { return byteSize; }
    public void setByteSize(Long value) { byteSize = value; }
    public byte[] getFileContent() { return fileContent; }
    public void setFileContent(byte[] value) { fileContent = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
