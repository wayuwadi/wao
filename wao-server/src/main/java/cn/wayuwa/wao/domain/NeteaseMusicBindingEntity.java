package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 成员自己的网易云音乐授权；令牌以 AES-256-GCM 密文保存。 */
@TableName("netease_music_binding")
public class NeteaseMusicBindingEntity {
    @TableId
    private String memberId;
    private byte[] accessTokenCiphertext;
    private byte[] accessTokenNonce;
    private byte[] refreshTokenCiphertext;
    private byte[] refreshTokenNonce;
    private LocalDateTime tokenExpiresAt;
    private String neteaseUserId;
    private String nickname;
    private String avatarUrl;
    private String deviceId;
    private String status;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public byte[] getAccessTokenCiphertext() { return accessTokenCiphertext; }
    public void setAccessTokenCiphertext(byte[] value) { this.accessTokenCiphertext = value; }
    public byte[] getAccessTokenNonce() { return accessTokenNonce; }
    public void setAccessTokenNonce(byte[] value) { this.accessTokenNonce = value; }
    public byte[] getRefreshTokenCiphertext() { return refreshTokenCiphertext; }
    public void setRefreshTokenCiphertext(byte[] value) { this.refreshTokenCiphertext = value; }
    public byte[] getRefreshTokenNonce() { return refreshTokenNonce; }
    public void setRefreshTokenNonce(byte[] value) { this.refreshTokenNonce = value; }
    public LocalDateTime getTokenExpiresAt() { return tokenExpiresAt; }
    public void setTokenExpiresAt(LocalDateTime value) { this.tokenExpiresAt = value; }
    public String getNeteaseUserId() { return neteaseUserId; }
    public void setNeteaseUserId(String value) { this.neteaseUserId = value; }
    public String getNickname() { return nickname; }
    public void setNickname(String value) { this.nickname = value; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String value) { this.avatarUrl = value; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String value) { this.deviceId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public String getLastError() { return lastError; }
    public void setLastError(String value) { this.lastError = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { this.createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { this.updatedAt = value; }
}
