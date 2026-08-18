package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 成员私有工作台；分组和链接以版本化 JSON 快照保存。 */
@TableName("personal_workspace")
public class PersonalWorkspaceEntity {
    @TableId private String id;
    private String ownerMemberId;
    private String projectId;
    private String name;
    private String descriptionText;
    private String contentJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(String value) { ownerMemberId = value; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String value) { projectId = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getDescriptionText() { return descriptionText; }
    public void setDescriptionText(String value) { descriptionText = value; }
    public String getContentJson() { return contentJson; }
    public void setContentJson(String value) { contentJson = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
