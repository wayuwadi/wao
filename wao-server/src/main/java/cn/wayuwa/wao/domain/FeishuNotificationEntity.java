package cn.wayuwa.wao.domain;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
/** 已发送的飞书通知，保证重启后卡片回调仍可归属。 */
@TableName("feishu_notification")
public class FeishuNotificationEntity {
 @TableId private String id; private String jobId; private LocalDateTime sentAt;
 public String getId(){return id;} public void setId(String v){id=v;} public String getJobId(){return jobId;} public void setJobId(String v){jobId=v;} public LocalDateTime getSentAt(){return sentAt;} public void setSentAt(LocalDateTime v){sentAt=v;}
}
