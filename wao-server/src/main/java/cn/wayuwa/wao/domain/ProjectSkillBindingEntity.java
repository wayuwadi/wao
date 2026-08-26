package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 项目固定启用的审核 Skill 版本；升级通过替换绑定完成。 */
@TableName("project_skill_binding")
public class ProjectSkillBindingEntity {
    @TableId private String id;
    private String projectId;
    private String skillId;
    private String skillVersionId;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId(){return id;} public void setId(String v){id=v;}
    public String getProjectId(){return projectId;} public void setProjectId(String v){projectId=v;}
    public String getSkillId(){return skillId;} public void setSkillId(String v){skillId=v;}
    public String getSkillVersionId(){return skillVersionId;} public void setSkillVersionId(String v){skillVersionId=v;}
    public String getCreatedBy(){return createdBy;} public void setCreatedBy(String v){createdBy=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
