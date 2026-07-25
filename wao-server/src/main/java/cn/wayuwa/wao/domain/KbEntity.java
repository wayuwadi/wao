package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 知识库表实体。 */
@TableName("kb")
public class KbEntity {

    @TableId
    private String id;
    private String name;
    private String icon;
    private String color;
    private String projectId;
    private Boolean auto;
    private String description;
    private String groupId;
    private String perm;
    private Integer ord;

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
}
