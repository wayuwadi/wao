package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 用户上传并公开托管的单文件 HTML 页面。 */
@TableName("hosted_page")
public class HostedPageEntity {
    @TableId private String id;
    private String slug;
    private String title;
    private String fileName;
    private byte[] htmlContent;
    private byte[] iconContent;
    private String iconContentType;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    public String getId() { return id; } public void setId(String value) { id = value; }
    public String getSlug() { return slug; } public void setSlug(String value) { slug = value; }
    public String getTitle() { return title; } public void setTitle(String value) { title = value; }
    public String getFileName() { return fileName; } public void setFileName(String value) { fileName = value; }
    public byte[] getHtmlContent() { return htmlContent; } public void setHtmlContent(byte[] value) { htmlContent = value; }
    public byte[] getIconContent() { return iconContent; } public void setIconContent(byte[] value) { iconContent = value; }
    public String getIconContentType() { return iconContentType; } public void setIconContentType(String value) { iconContentType = value; }
    public String getCreatedBy() { return createdBy; } public void setCreatedBy(String value) { createdBy = value; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
