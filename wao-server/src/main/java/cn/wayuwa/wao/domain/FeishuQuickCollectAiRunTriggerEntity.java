package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 飞书快速收集原消息上的飞机表情触发记录。 */
@TableName("feishu_quick_collect_ai_run_trigger")
public class FeishuQuickCollectAiRunTriggerEntity {
    @TableId
    private String eventKey;
    private String eventId;
    private String messageId;
    private String operatorOpenId;
    private String taskId;
    private String aiRunId;
    private LocalDateTime createdAt;

    public String getEventKey() { return eventKey; }
    public void setEventKey(String value) { eventKey = value; }
    public String getEventId() { return eventId; }
    public void setEventId(String value) { eventId = value; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String value) { messageId = value; }
    public String getOperatorOpenId() { return operatorOpenId; }
    public void setOperatorOpenId(String value) { operatorOpenId = value; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String value) { taskId = value; }
    public String getAiRunId() { return aiRunId; }
    public void setAiRunId(String value) { aiRunId = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
}
