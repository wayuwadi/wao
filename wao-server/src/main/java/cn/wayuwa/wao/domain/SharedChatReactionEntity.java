package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 共享聊天的一次成员或飞书表情回复。 */
@TableName("shared_chat_reaction")
public class SharedChatReactionEntity {
    @TableId
    private String id;
    private String messageId;
    private String reactorKey;
    private String memberId;
    private String reactorName;
    private String emojiType;
    private String source;
    private String feishuReactionId;
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getReactorKey() { return reactorKey; }
    public void setReactorKey(String reactorKey) { this.reactorKey = reactorKey; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public String getReactorName() { return reactorName; }
    public void setReactorName(String reactorName) { this.reactorName = reactorName; }
    public String getEmojiType() { return emojiType; }
    public void setEmojiType(String emojiType) { this.emojiType = emojiType; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getFeishuReactionId() { return feishuReactionId; }
    public void setFeishuReactionId(String feishuReactionId) { this.feishuReactionId = feishuReactionId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
