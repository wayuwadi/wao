package cn.wayuwa.wao.domain;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
@TableName("idea_artifact")
public class IdeaArtifactEntity {
    @TableId private String id; private String ideaId; private String kind; private String docId;
    private String title; private String createdBy; private LocalDateTime createdAt;
    public String getId(){return id;} public void setId(String v){id=v;} public String getIdeaId(){return ideaId;} public void setIdeaId(String v){ideaId=v;}
    public String getKind(){return kind;} public void setKind(String v){kind=v;} public String getDocId(){return docId;} public void setDocId(String v){docId=v;}
    public String getTitle(){return title;} public void setTitle(String v){title=v;} public String getCreatedBy(){return createdBy;} public void setCreatedBy(String v){createdBy=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
}
