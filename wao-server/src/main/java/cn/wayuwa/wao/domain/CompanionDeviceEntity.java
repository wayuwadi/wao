package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 一台仅能读取桌宠活动的已授权 Windows 设备。 */
@TableName("companion_device")
public class CompanionDeviceEntity {
    @TableId
    private String id;
    private String clientDeviceId;
    private String accountId;
    private String memberId;
    private String name;
    private String platform;
    private String tokenHash;
    private LocalDateTime createdAt;
    private LocalDateTime lastSeenAt;
    private LocalDateTime readBeforeAt;
    private LocalDateTime revokedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getClientDeviceId() { return clientDeviceId; }
    public void setClientDeviceId(String clientDeviceId) { this.clientDeviceId = clientDeviceId; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public LocalDateTime getReadBeforeAt() { return readBeforeAt; }
    public void setReadBeforeAt(LocalDateTime readBeforeAt) { this.readBeforeAt = readBeforeAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; }
}
