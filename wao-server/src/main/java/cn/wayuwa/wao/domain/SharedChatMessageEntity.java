package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 组织共享聊天室消息；正文持久化与飞书投递状态相互独立。 */
@TableName("shared_chat_message")
public class SharedChatMessageEntity {
    @TableId
    private String id;
    private String memberId;
    private String clientToken;
    private String source;
    private String externalSenderId;
    private String externalSenderName;
    private String content;
    private String imageId;
    private String imageName;
    private String feishuChatId;
    private String feishuMessageId;
    private String feishuSourceHash;
    private String replyToId;
    private Boolean deleted;
    private String feishuStatus;
    private String feishuError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getExternalSenderId() { return externalSenderId; }
    public void setExternalSenderId(String externalSenderId) { this.externalSenderId = externalSenderId; }
    public String getExternalSenderName() { return externalSenderName; }
    public void setExternalSenderName(String externalSenderName) { this.externalSenderName = externalSenderName; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getImageId() { return imageId; }
    public void setImageId(String imageId) { this.imageId = imageId; }
    public String getImageName() { return imageName; }
    public void setImageName(String imageName) { this.imageName = imageName; }
    public String getFeishuChatId() { return feishuChatId; }
    public void setFeishuChatId(String feishuChatId) { this.feishuChatId = feishuChatId; }
    public String getFeishuMessageId() { return feishuMessageId; }
    public void setFeishuMessageId(String feishuMessageId) { this.feishuMessageId = feishuMessageId; }
    public String getFeishuSourceHash() { return feishuSourceHash; }
    public void setFeishuSourceHash(String value) { feishuSourceHash = value; }
    public String getReplyToId() { return replyToId; }
    public void setReplyToId(String replyToId) { this.replyToId = replyToId; }
    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean deleted) { this.deleted = deleted; }
    public String getFeishuStatus() { return feishuStatus; }
    public void setFeishuStatus(String feishuStatus) { this.feishuStatus = feishuStatus; }
    public String getFeishuError() { return feishuError; }
    public void setFeishuError(String feishuError) { this.feishuError = feishuError; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
