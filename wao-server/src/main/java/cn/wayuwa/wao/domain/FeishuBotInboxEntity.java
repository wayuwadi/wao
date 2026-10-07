package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 已认证回调收件箱；事件体加密，完成后清除内容。 */
@TableName("feishu_bot_inbox")
public class FeishuBotInboxEntity {
    @TableId private String id;
    private String eventId; private String messageId; private String appId;
    private String webhookId; private String captureKind;
    private byte[] payloadCiphertext; private byte[] payloadNonce;
    private String status; private Integer attempts; private LocalDateTime availableAt;
    private LocalDateTime updatedAt; private String errorCode;
    private String leaseToken;
    public String getLeaseToken() { return leaseToken; } public void setLeaseToken(String v) { leaseToken=v; }
    public String getId() { return id; } public void setId(String v) { id=v; }
    public String getEventId() { return eventId; } public void setEventId(String v) { eventId=v; }
    public String getMessageId() { return messageId; } public void setMessageId(String v) { messageId=v; }
    public String getAppId() { return appId; } public void setAppId(String v) { appId=v; }
    public String getWebhookId() { return webhookId; } public void setWebhookId(String v) { webhookId=v; }
    public String getCaptureKind() { return captureKind; } public void setCaptureKind(String v) { captureKind=v; }
    public byte[] getPayloadCiphertext() { return payloadCiphertext; } public void setPayloadCiphertext(byte[] v) { payloadCiphertext=v; }
    public byte[] getPayloadNonce() { return payloadNonce; } public void setPayloadNonce(byte[] v) { payloadNonce=v; }
    public String getStatus() { return status; } public void setStatus(String v) { status=v; }
    public Integer getAttempts() { return attempts; } public void setAttempts(Integer v) { attempts=v; }
    public LocalDateTime getAvailableAt() { return availableAt; } public void setAvailableAt(LocalDateTime v) { availableAt=v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(LocalDateTime v) { updatedAt=v; }
    public String getErrorCode() { return errorCode; } public void setErrorCode(String v) { errorCode=v; }
}
