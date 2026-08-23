package cn.wayuwa.wao.domain;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
@TableName("idea_research_run")
public class IdeaResearchRunEntity {
    @TableId private String id; private String ideaId; private String memberId; private String runnerId; private String claimToken;
    private String status; private String promptText; private String responseText; private String errorText; private String docId;
    private LocalDateTime createdAt; private LocalDateTime claimedAt; private LocalDateTime finishedAt; private LocalDateTime updatedAt;
    public String getId(){return id;} public void setId(String v){id=v;} public String getIdeaId(){return ideaId;} public void setIdeaId(String v){ideaId=v;}
    public String getMemberId(){return memberId;} public void setMemberId(String v){memberId=v;} public String getRunnerId(){return runnerId;} public void setRunnerId(String v){runnerId=v;}
    public String getClaimToken(){return claimToken;} public void setClaimToken(String v){claimToken=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getPromptText(){return promptText;} public void setPromptText(String v){promptText=v;} public String getResponseText(){return responseText;} public void setResponseText(String v){responseText=v;}
    public String getErrorText(){return errorText;} public void setErrorText(String v){errorText=v;} public String getDocId(){return docId;} public void setDocId(String v){docId=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;} public LocalDateTime getClaimedAt(){return claimedAt;} public void setClaimedAt(LocalDateTime v){claimedAt=v;}
    public LocalDateTime getFinishedAt(){return finishedAt;} public void setFinishedAt(LocalDateTime v){finishedAt=v;} public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
