package cn.wayuwa.wao.domain;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
@TableName("idea_iteration")
public class IdeaIterationEntity {
    @TableId private String id; private String ideaId; private String taskId; private String aiRunId;
    private String objective; private String acceptanceCriteria; private String status; private String feedback;
    private String resultSummary; private String reportUrl; private String createdBy; private LocalDateTime createdAt; private LocalDateTime finishedAt;
    public String getId(){return id;} public void setId(String v){id=v;} public String getIdeaId(){return ideaId;} public void setIdeaId(String v){ideaId=v;}
    public String getTaskId(){return taskId;} public void setTaskId(String v){taskId=v;} public String getAiRunId(){return aiRunId;} public void setAiRunId(String v){aiRunId=v;}
    public String getObjective(){return objective;} public void setObjective(String v){objective=v;} public String getAcceptanceCriteria(){return acceptanceCriteria;} public void setAcceptanceCriteria(String v){acceptanceCriteria=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;} public String getFeedback(){return feedback;} public void setFeedback(String v){feedback=v;}
    public String getResultSummary(){return resultSummary;} public void setResultSummary(String v){resultSummary=v;} public String getReportUrl(){return reportUrl;} public void setReportUrl(String v){reportUrl=v;}
    public String getCreatedBy(){return createdBy;} public void setCreatedBy(String v){createdBy=v;} public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getFinishedAt(){return finishedAt;} public void setFinishedAt(LocalDateTime v){finishedAt=v;}
}
