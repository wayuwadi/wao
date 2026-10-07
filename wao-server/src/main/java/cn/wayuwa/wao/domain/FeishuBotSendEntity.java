package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 仅保存投递结果，不保存 Webhook、消息正文或上游异常。 */
@TableName("feishu_bot_send")
public class FeishuBotSendEntity {
    @TableId private String id;
    private String webhookId;
    private String kind;
    private String status;
    private String errorCode;
    private LocalDateTime occurredAt;
    public String getId() { return id; } public void setId(String v) { id = v; }
    public String getWebhookId() { return webhookId; } public void setWebhookId(String v) { webhookId = v; }
    public String getKind() { return kind; } public void setKind(String v) { kind = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public String getErrorCode() { return errorCode; } public void setErrorCode(String v) { errorCode = v; }
    public LocalDateTime getOccurredAt() { return occurredAt; } public void setOccurredAt(LocalDateTime v) { occurredAt = v; }
}
