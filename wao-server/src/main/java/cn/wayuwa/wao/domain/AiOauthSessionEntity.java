package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 一次进行中的 AI 账号 OAuth 登录。
 *
 * <p>PKCE 的 {@code code_verifier} 只留在服务端：前端拿到的只有授权地址和 state，
 * 因此即使浏览器侧的授权码被截获，没有 verifier 也换不到令牌。换到令牌或过期后立即删除。
 */
@TableName("ai_account_oauth_session")
public class AiOauthSessionEntity {

    @TableId
    private String state;
    private String provider;
    private String codeVerifier;
    private String label;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getCodeVerifier() { return codeVerifier; }
    public void setCodeVerifier(String codeVerifier) { this.codeVerifier = codeVerifier; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
