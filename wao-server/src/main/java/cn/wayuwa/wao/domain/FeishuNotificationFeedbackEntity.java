package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 飞书定时通知被点击后的真实反馈。 */
@TableName("feishu_notification_feedback")
public class FeishuNotificationFeedbackEntity {
    @TableId private String id;
    private String notificationId;
    private String jobId;
    private LocalDateTime notificationAt;
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String reactionId;
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String reactionAction;
    public String getReactionId() { return reactionId; } public void setReactionId(String v) { reactionId = v; }
    public String getReactionAction() { return reactionAction; } public void setReactionAction(String v) { reactionAction = v; }
    private String action;
    private String operatorOpenId;
    private String operatorName;
    private LocalDateTime actedAt;
    public String getId() { return id; } public void setId(String v) { id = v; }
    public String getNotificationId() { return notificationId; } public void setNotificationId(String v) { notificationId = v; }
    public String getJobId() { return jobId; } public void setJobId(String v) { jobId = v; }
    public LocalDateTime getNotificationAt() { return notificationAt; } public void setNotificationAt(LocalDateTime v) { notificationAt = v; }
    public String getAction() { return action; } public void setAction(String v) { action = v; }
    public String getOperatorOpenId() { return operatorOpenId; } public void setOperatorOpenId(String v) { operatorOpenId = v; }
    public String getOperatorName() { return operatorName; } public void setOperatorName(String v) { operatorName = v; }
    public LocalDateTime getActedAt() { return actedAt; } public void setActedAt(LocalDateTime v) { actedAt = v; }
}
