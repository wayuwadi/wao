package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** CalDAV 账户表实体。密码以 AES-256-GCM 密文 + nonce 存储，仅在同步时按需解密。 */
@TableName("caldav_account")
public class CaldavAccountEntity {

    @TableId
    private String id;
    private String memberId;
    private String name;
    private String serverUrl;
    private String username;
    private byte[] passwordCiphertext;
    private byte[] passwordNonce;
    /** ok / error。 */
    private String status;
    private LocalDateTime lastSyncAt;
    private String syncError;
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public byte[] getPasswordCiphertext() { return passwordCiphertext; }
    public void setPasswordCiphertext(byte[] passwordCiphertext) { this.passwordCiphertext = passwordCiphertext; }
    public byte[] getPasswordNonce() { return passwordNonce; }
    public void setPasswordNonce(byte[] passwordNonce) { this.passwordNonce = passwordNonce; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(LocalDateTime lastSyncAt) { this.lastSyncAt = lastSyncAt; }
    public String getSyncError() { return syncError; }
    public void setSyncError(String syncError) { this.syncError = syncError; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
