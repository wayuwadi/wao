package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 组织共享账号资源；密码只以 AES-256-GCM 密文保存。 */
@TableName("account_resource")
public class AccountResourceEntity {
    @TableId private String id;
    private String platform;
    private String accountLabel;
    private String loginIdentifier;
    private String phoneNumber;
    private byte[] secretCiphertext;
    private byte[] secretNonce;
    private String notes;
    private Boolean enabled;
    private Integer revision;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getPlatform() { return platform; }
    public void setPlatform(String value) { platform = value; }
    public String getAccountLabel() { return accountLabel; }
    public void setAccountLabel(String value) { accountLabel = value; }
    public String getLoginIdentifier() { return loginIdentifier; }
    public void setLoginIdentifier(String value) { loginIdentifier = value; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String value) { phoneNumber = value; }
    public byte[] getSecretCiphertext() { return secretCiphertext; }
    public void setSecretCiphertext(byte[] value) { secretCiphertext = value; }
    public byte[] getSecretNonce() { return secretNonce; }
    public void setSecretNonce(byte[] value) { secretNonce = value; }
    public String getNotes() { return notes; }
    public void setNotes(String value) { notes = value; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean value) { enabled = value; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer value) { revision = value; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String value) { createdBy = value; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String value) { updatedBy = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
