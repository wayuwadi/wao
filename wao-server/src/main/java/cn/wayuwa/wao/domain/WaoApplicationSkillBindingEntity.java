package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** WAO 应用默认装配的审核 Skill 固定版本。 */
@TableName("wao_application_skill_binding")
public class WaoApplicationSkillBindingEntity {
    @TableId private String id;
    private String applicationId;
    private String skillId;
    private String skillVersionId;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId(){return id;} public void setId(String value){id=value;}
    public String getApplicationId(){return applicationId;} public void setApplicationId(String value){applicationId=value;}
    public String getSkillId(){return skillId;} public void setSkillId(String value){skillId=value;}
    public String getSkillVersionId(){return skillVersionId;} public void setSkillVersionId(String value){skillVersionId=value;}
    public String getCreatedBy(){return createdBy;} public void setCreatedBy(String value){createdBy=value;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime value){createdAt=value;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime value){updatedAt=value;}
}
