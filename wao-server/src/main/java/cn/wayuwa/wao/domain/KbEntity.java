package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 知识库表实体。{@code deletedAt} 非空表示已在回收站（软删除，见 ADR-018）。 */
@TableName("kb")
public class KbEntity {

    @TableId
    private String id;
    @com.baomidou.mybatisplus.annotation.TableField(
        insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
        updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private String directoryJson;
    public String getDirectoryJson() { return directoryJson; }
    public void setDirectoryJson(String value) { directoryJson = value; }
    private String name;
    private String icon;
    private String color;
    private String projectId;
    private Boolean auto;
    private String description;
    private String groupId;
    private String perm;
    private Integer ord;
    private Boolean notes;
    private LocalDateTime deletedAt;
    private String deletedBy;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public Boolean getAuto() { return auto; }
    public void setAuto(Boolean auto) { this.auto = auto; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getPerm() { return perm; }
    public void setPerm(String perm) { this.perm = perm; }
    public Integer getOrd() { return ord; }
    public void setOrd(Integer ord) { this.ord = ord; }
    public Boolean getNotes() { return notes; }
    public void setNotes(Boolean notes) { this.notes = notes; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public String getDeletedBy() { return deletedBy; }
    public void setDeletedBy(String deletedBy) { this.deletedBy = deletedBy; }
}
