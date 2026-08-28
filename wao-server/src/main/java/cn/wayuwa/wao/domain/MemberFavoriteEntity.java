package cn.wayuwa.wao.domain;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
/** 成员收藏的一条链接（模块 / 项目 / 任务 / 文档），按成员维度隔离。 */
@TableName("member_favorite")
public class MemberFavoriteEntity {
 @TableId private String id; private String memberId; private String targetType; private String targetId; private LocalDateTime createdAt;
 public String getId(){return id;} public void setId(String v){id=v;}
 public String getMemberId(){return memberId;} public void setMemberId(String v){memberId=v;}
 public String getTargetType(){return targetType;} public void setTargetType(String v){targetType=v;}
 public String getTargetId(){return targetId;} public void setTargetId(String v){targetId=v;}
 public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
}
