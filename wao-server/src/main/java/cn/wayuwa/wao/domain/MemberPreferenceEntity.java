package cn.wayuwa.wao.domain;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableName;
/** 成员级偏好键值（如左侧菜单顺序），复合主键 (member_id, pref_key)，用 Mapper 自定义 SQL 读写。 */
@TableName("member_preference")
public class MemberPreferenceEntity {
 private String memberId; private String prefKey; private String prefValue; private LocalDateTime updatedAt;
 public String getMemberId(){return memberId;} public void setMemberId(String v){memberId=v;}
 public String getPrefKey(){return prefKey;} public void setPrefKey(String v){prefKey=v;}
 public String getPrefValue(){return prefValue;} public void setPrefValue(String v){prefValue=v;}
 public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
