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
    private String content;
    private String imageId;
    private String imageName;
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
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getImageId() { return imageId; }
    public void setImageId(String imageId) { this.imageId = imageId; }
    public String getImageName() { return imageName; }
    public void setImageName(String imageName) { this.imageName = imageName; }
    public String getFeishuStatus() { return feishuStatus; }
    public void setFeishuStatus(String feishuStatus) { this.feishuStatus = feishuStatus; }
    public String getFeishuError() { return feishuError; }
    public void setFeishuError(String feishuError) { this.feishuError = feishuError; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
