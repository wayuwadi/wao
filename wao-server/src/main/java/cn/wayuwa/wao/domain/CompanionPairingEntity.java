package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 十分钟有效、需由 WAO 网页会话批准的桌宠配对。 */
@TableName("companion_pairing")
public class CompanionPairingEntity {
    @TableId
    private String id;
    private String codeHash;
    private String pollSecretHash;
    private String clientDeviceId;
    private String deviceName;
    private String platform;
    private String status;
    private String accountId;
    private String memberId;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime approvedAt;
    private LocalDateTime consumedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public String getPollSecretHash() { return pollSecretHash; }
    public void setPollSecretHash(String pollSecretHash) { this.pollSecretHash = pollSecretHash; }
    public String getClientDeviceId() { return clientDeviceId; }
    public void setClientDeviceId(String clientDeviceId) { this.clientDeviceId = clientDeviceId; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }
    public LocalDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(LocalDateTime consumedAt) { this.consumedAt = consumedAt; }
}
