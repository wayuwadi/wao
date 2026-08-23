package cn.wayuwa.wao.domain;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
@TableName("idea_task_link")
public class IdeaTaskLinkEntity {
    @TableId private String id; private String ideaId; private String taskId; private Boolean requiredMvp;
    private Integer ord; private String createdBy; private LocalDateTime createdAt;
    public String getId(){return id;} public void setId(String v){id=v;} public String getIdeaId(){return ideaId;} public void setIdeaId(String v){ideaId=v;}
    public String getTaskId(){return taskId;} public void setTaskId(String v){taskId=v;} public Boolean getRequiredMvp(){return requiredMvp;} public void setRequiredMvp(Boolean v){requiredMvp=v;}
    public Integer getOrd(){return ord;} public void setOrd(Integer v){ord=v;} public String getCreatedBy(){return createdBy;} public void setCreatedBy(String v){createdBy=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
}
