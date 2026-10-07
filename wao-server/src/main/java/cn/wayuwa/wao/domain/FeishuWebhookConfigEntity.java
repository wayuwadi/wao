package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 飞书自定义机器人 Webhook；地址以 AES-256-GCM 密文和随机 nonce 分列保存。 */
@TableName("feishu_webhook_config")
public class FeishuWebhookConfigEntity {

    @TableId
    private String id;
    private String name;
    private String description;
    private byte[] webhookCiphertext;
    private byte[] webhookNonce;
    private Boolean enabled;
    /** 值为 SYSTEM 表示现有系统与项目通知的默认机器人；其余记录为 null。 */
    private String defaultMarker;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String captureKind;
    private String mentionId;
    private String mentionName;
    private String chatId;
    private String appId;
    private Long revision;

    public String getCaptureKind() { return captureKind; }
    public void setCaptureKind(String value) { captureKind = value; }
    public String getMentionId() { return mentionId; }
    public void setMentionId(String value) { mentionId = value; }
    public String getMentionName() { return mentionName; }
    public void setMentionName(String value) { mentionName = value; }
    public String getChatId() { return chatId; }
    public void setChatId(String value) { chatId = value; }
    public String getAppId() { return appId; }
    public void setAppId(String value) { appId = value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision = value; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public byte[] getWebhookCiphertext() { return webhookCiphertext; }
    public void setWebhookCiphertext(byte[] webhookCiphertext) { this.webhookCiphertext = webhookCiphertext; }
    public byte[] getWebhookNonce() { return webhookNonce; }
    public void setWebhookNonce(byte[] webhookNonce) { this.webhookNonce = webhookNonce; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getDefaultMarker() { return defaultMarker; }
    public void setDefaultMarker(String defaultMarker) { this.defaultMarker = defaultMarker; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
