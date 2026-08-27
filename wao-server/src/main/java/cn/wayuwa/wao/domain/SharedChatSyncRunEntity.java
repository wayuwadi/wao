package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 一次可审计的共享聊天当日核对；空间口径是本次新增正文和图片原始字节。 */
@TableName("shared_chat_sync_run")
public class SharedChatSyncRunEntity {
    @TableId private String id;
    private String triggerType;
    private String chatId;
    private String chatName;
    private LocalDate syncDay;
    private String status;
    private Integer messagesScanned;
    private Integer messagesInserted;
    private Integer messagesUpdated;
    private Long contentBytesAdded;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public String getId() { return id; } public void setId(String v) { id = v; }
    public String getTriggerType() { return triggerType; } public void setTriggerType(String v) { triggerType = v; }
    public String getChatId() { return chatId; } public void setChatId(String v) { chatId = v; }
    public String getChatName() { return chatName; } public void setChatName(String v) { chatName = v; }
    public LocalDate getSyncDay() { return syncDay; } public void setSyncDay(LocalDate v) { syncDay = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public Integer getMessagesScanned() { return messagesScanned; } public void setMessagesScanned(Integer v) { messagesScanned = v; }
    public Integer getMessagesInserted() { return messagesInserted; } public void setMessagesInserted(Integer v) { messagesInserted = v; }
    public Integer getMessagesUpdated() { return messagesUpdated; } public void setMessagesUpdated(Integer v) { messagesUpdated = v; }
    public Long getContentBytesAdded() { return contentBytesAdded; } public void setContentBytesAdded(Long v) { contentBytesAdded = v; }
    public String getErrorMessage() { return errorMessage; } public void setErrorMessage(String v) { errorMessage = v; }
    public LocalDateTime getStartedAt() { return startedAt; } public void setStartedAt(LocalDateTime v) { startedAt = v; }
    public LocalDateTime getFinishedAt() { return finishedAt; } public void setFinishedAt(LocalDateTime v) { finishedAt = v; }
}
