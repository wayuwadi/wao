package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 页面 AI 助手的一次短期 Codex 队列记录。 */
@TableName("ai_assistant_run")
public class AiAssistantRunEntity {

    @TableId
    private String id;
    private String memberId;
    private String aiCapabilityId;
    private String requestedModel;
    private String reasoningEffort;
    private String usagePurpose;
    private Boolean webSearchEnabled;
    private String conversationId;
    private String userMessageId;
    private String referenceImageIds;
    private String runnerId;
    private String claimToken;
    private String status;
    /** 终态时必须真正写回 NULL，不受 MyBatis-Plus 默认忽略 null 字段影响。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String promptText;
    /** Provider 最终回答前由隔离 Codex Runner 生成的有界联网材料。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String webContext;
    private String responseText;
    private String errorText;
    private LocalDateTime createdAt;
    private LocalDateTime claimedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public String getUsagePurpose() { return usagePurpose; }
    public void setUsagePurpose(String usagePurpose) { this.usagePurpose = usagePurpose; }

    public String getAiCapabilityId() { return aiCapabilityId; }
    public void setAiCapabilityId(String aiCapabilityId) { this.aiCapabilityId = aiCapabilityId; }
    public String getRequestedModel() { return requestedModel; }
    public void setRequestedModel(String requestedModel) { this.requestedModel = requestedModel; }
    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }
    public Boolean getWebSearchEnabled() { return webSearchEnabled; }
    public void setWebSearchEnabled(Boolean webSearchEnabled) { this.webSearchEnabled = webSearchEnabled; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getUserMessageId() { return userMessageId; }
    public void setUserMessageId(String userMessageId) { this.userMessageId = userMessageId; }
    public String getReferenceImageIds() { return referenceImageIds; }
    public void setReferenceImageIds(String referenceImageIds) { this.referenceImageIds = referenceImageIds; }
    public String getRunnerId() { return runnerId; }
    public void setRunnerId(String runnerId) { this.runnerId = runnerId; }
    public String getClaimToken() { return claimToken; }
    public void setClaimToken(String claimToken) { this.claimToken = claimToken; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPromptText() { return promptText; }
    public void setPromptText(String promptText) { this.promptText = promptText; }
    public String getWebContext() { return webContext; }
    public void setWebContext(String webContext) { this.webContext = webContext; }
    public String getResponseText() { return responseText; }
    public void setResponseText(String responseText) { this.responseText = responseText; }
    public String getErrorText() { return errorText; }
    public void setErrorText(String errorText) { this.errorText = errorText; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getClaimedAt() { return claimedAt; }
    public void setClaimedAt(LocalDateTime claimedAt) { this.claimedAt = claimedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
