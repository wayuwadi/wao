package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 飞书 @机器人收集事件的持久去重与结果记录。 */
@TableName("feishu_quick_collect_event")
public class FeishuQuickCollectEventEntity {
    @TableId
    private String eventKey;
    private String eventId;
    private String messageId;
    private String chatId;
    private String senderOpenId;
    private String captureKind;
    private String status;
    private String targetId;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getEventKey() { return eventKey; }
    public void setEventKey(String eventKey) { this.eventKey = eventKey; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
    public String getSenderOpenId() { return senderOpenId; }
    public void setSenderOpenId(String senderOpenId) { this.senderOpenId = senderOpenId; }
    public String getCaptureKind() { return captureKind; }
    public void setCaptureKind(String captureKind) { this.captureKind = captureKind; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
