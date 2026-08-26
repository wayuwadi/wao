package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 共享聊天消息的真实阅读事实；WAO 页面与飞书事件分别记录来源。 */
@TableName("shared_chat_message_read")
public class SharedChatMessageReadEntity {
    @TableId
    private String id;
    private String messageId;
    private String readerKey;
    private String memberId;
    private String source;
    private LocalDateTime readAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getReaderKey() { return readerKey; }
    public void setReaderKey(String readerKey) { this.readerKey = readerKey; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public LocalDateTime getReadAt() { return readAt; }
    public void setReadAt(LocalDateTime readAt) { this.readAt = readAt; }
}
