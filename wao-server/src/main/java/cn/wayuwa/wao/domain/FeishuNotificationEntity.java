package cn.wayuwa.wao.domain;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
/** 已发送的飞书通知，保证重启后卡片回调仍可归属。 */
@TableName("feishu_notification")
public class FeishuNotificationEntity {
 private String cardTitle;
 private String cardBody;
 private String messageId;
 private String feedbackMessageId;
 public String getCardTitle(){return cardTitle;} public void setCardTitle(String v){cardTitle=v;}
 public String getCardBody(){return cardBody;} public void setCardBody(String v){cardBody=v;}
 public String getMessageId(){return messageId;} public void setMessageId(String v){messageId=v;}
 public String getFeedbackMessageId(){return feedbackMessageId;} public void setFeedbackMessageId(String v){feedbackMessageId=v;}
 @TableId private String id; private String jobId; private LocalDateTime sentAt;
 public String getId(){return id;} public void setId(String v){id=v;} public String getJobId(){return jobId;} public void setJobId(String v){jobId=v;} public LocalDateTime getSentAt(){return sentAt;} public void setSentAt(LocalDateTime v){sentAt=v;}
}
