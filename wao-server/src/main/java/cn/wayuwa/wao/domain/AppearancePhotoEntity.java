package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 按成员隔离的形象实拍原件；列表查询默认不读取二进制正文。 */
@TableName("appearance_photo")
public class AppearancePhotoEntity {
    @TableId private String id;
    private String memberId;
    private String fileName;
    private String contentType;
    private Long byteSize;
    @TableField(select = false) private byte[] fileContent;
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String value) { memberId = value; }
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
}
