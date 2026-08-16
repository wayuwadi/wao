package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 短期 OAuth state，把网易云回调安全绑定到发起授权的 WAO 成员。 */
@TableName("netease_music_oauth_state")
public class NeteaseMusicOauthStateEntity {
    @TableId
    private String state;
    private String memberId;
    private String deviceId;
    private String netStatus;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;

    public String getState() { return state; }
    public void setState(String value) { this.state = value; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String value) { this.memberId = value; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String value) { this.deviceId = value; }
    public String getNetStatus() { return netStatus; }
    public void setNetStatus(String value) { this.netStatus = value; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime value) { this.expiresAt = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { this.createdAt = value; }
}
