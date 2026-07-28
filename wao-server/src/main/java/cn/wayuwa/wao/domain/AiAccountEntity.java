package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 集中管理的 AI 工具账号（Claude Code / Codex / Qoder）。凭据加密落库，明文只解密给已配对 Runner。 */
@TableName("ai_account")
public class AiAccountEntity {

    @TableId
    private String id;
    private String provider;
    private String label;
    private String email;
    private String credentialJson;
    private Boolean enabled;
    private String quotaStatus;
    private String quotaMessage;
    private String quotaRawJson;
    private LocalDateTime lastSyncedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String authMethod;
    private String plan;
    private LocalDateTime tokenExpiresAt;
    private String quotaWindow;
    private Integer quotaPercent;
    private LocalDateTime quotaResetsAt;
    private String baseUrl;
    private String model;
    private String apiProtocol;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getCredentialJson() { return credentialJson; }
    public void setCredentialJson(String credentialJson) { this.credentialJson = credentialJson; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getQuotaStatus() { return quotaStatus; }
    public void setQuotaStatus(String quotaStatus) { this.quotaStatus = quotaStatus; }

    public String getQuotaMessage() { return quotaMessage; }
    public void setQuotaMessage(String quotaMessage) { this.quotaMessage = quotaMessage; }

    public String getQuotaRawJson() { return quotaRawJson; }
    public void setQuotaRawJson(String quotaRawJson) { this.quotaRawJson = quotaRawJson; }

    public LocalDateTime getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(LocalDateTime lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getAuthMethod() { return authMethod; }
    public void setAuthMethod(String authMethod) { this.authMethod = authMethod; }

    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }

    public LocalDateTime getTokenExpiresAt() { return tokenExpiresAt; }
    public void setTokenExpiresAt(LocalDateTime tokenExpiresAt) { this.tokenExpiresAt = tokenExpiresAt; }

    public String getQuotaWindow() { return quotaWindow; }
    public void setQuotaWindow(String quotaWindow) { this.quotaWindow = quotaWindow; }

    public Integer getQuotaPercent() { return quotaPercent; }
    public void setQuotaPercent(Integer quotaPercent) { this.quotaPercent = quotaPercent; }

    public LocalDateTime getQuotaResetsAt() { return quotaResetsAt; }
    public void setQuotaResetsAt(LocalDateTime quotaResetsAt) { this.quotaResetsAt = quotaResetsAt; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getApiProtocol() { return apiProtocol; }
    public void setApiProtocol(String apiProtocol) { this.apiProtocol = apiProtocol; }
}
