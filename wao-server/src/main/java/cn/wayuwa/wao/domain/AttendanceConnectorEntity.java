package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 当前成员的考勤数据连接；密码只引用密码本，缓存会话继续加密保存。 */
@TableName("attendance_connector")
public class AttendanceConnectorEntity {
    @TableId private String memberId;
    private String provider; private String username; private String vaultItemId; private Boolean enabled;
    private String remoteEmployeeId; private byte[] sessionCiphertext; private byte[] sessionNonce;
    private String status; private LocalDateTime lastSyncedAt; private String lastError;
    private LocalDateTime createdAt; private LocalDateTime updatedAt;
    public String getMemberId(){return memberId;} public void setMemberId(String v){memberId=v;}
    public String getProvider(){return provider;} public void setProvider(String v){provider=v;}
    public String getUsername(){return username;} public void setUsername(String v){username=v;}
    public String getVaultItemId(){return vaultItemId;} public void setVaultItemId(String v){vaultItemId=v;}
    public Boolean getEnabled(){return enabled;} public void setEnabled(Boolean v){enabled=v;}
    public String getRemoteEmployeeId(){return remoteEmployeeId;} public void setRemoteEmployeeId(String v){remoteEmployeeId=v;}
    public byte[] getSessionCiphertext(){return sessionCiphertext;} public void setSessionCiphertext(byte[] v){sessionCiphertext=v;}
    public byte[] getSessionNonce(){return sessionNonce;} public void setSessionNonce(byte[] v){sessionNonce=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public LocalDateTime getLastSyncedAt(){return lastSyncedAt;} public void setLastSyncedAt(LocalDateTime v){lastSyncedAt=v;}
    public String getLastError(){return lastError;} public void setLastError(String v){lastError=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
