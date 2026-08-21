package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** Skill 市场公开发布者，与 WAO 内部成员身份隔离。 */
@TableName("skill_publisher")
public class SkillPublisherEntity {
    @TableId private String id;
    private String memberId;
    private String handle;
    private String displayName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String value) { memberId = value; }
    public String getHandle() { return handle; }
    public void setHandle(String value) { handle = value; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { displayName = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
