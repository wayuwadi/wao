package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 当前成员的一份形象管理结构化快照。 */
@TableName("appearance_workspace")
public class AppearanceWorkspaceEntity {
    @TableId private String memberId;
    private String contentJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getMemberId() { return memberId; }
    public void setMemberId(String value) { memberId = value; }
    public String getContentJson() { return contentJson; }
    public void setContentJson(String value) { contentJson = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
