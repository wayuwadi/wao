package cn.wayuwa.wao.domain;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
/** 仅保存服务端加密的个人表情凭证；不能返回给浏览器或日志。 */
@TableName("feishu_reaction_grant")
public class FeishuReactionGrantEntity {
    @TableId
    private String id;
    public String getId() { return id; } public void setId(String value) { id = value; }
    private String appId;
    public String getAppId() { return appId; } public void setAppId(String value) { appId = value; }
    private String openId;
    public String getOpenId() { return openId; } public void setOpenId(String value) { openId = value; }
    private String displayName;
    public String getDisplayName() { return displayName; } public void setDisplayName(String value) { displayName = value; }
    private byte[] accessCipher;
    public byte[] getAccessCipher() { return accessCipher; } public void setAccessCipher(byte[] value) { accessCipher = value; }
    private byte[] accessNonce;
    public byte[] getAccessNonce() { return accessNonce; } public void setAccessNonce(byte[] value) { accessNonce = value; }
    private byte[] refreshCipher;
    public byte[] getRefreshCipher() { return refreshCipher; } public void setRefreshCipher(byte[] value) { refreshCipher = value; }
    private byte[] refreshNonce;
    public byte[] getRefreshNonce() { return refreshNonce; } public void setRefreshNonce(byte[] value) { refreshNonce = value; }
    private java.time.LocalDateTime expiresAt;
    public java.time.LocalDateTime getExpiresAt() { return expiresAt; } public void setExpiresAt(java.time.LocalDateTime value) { expiresAt = value; }
    private java.time.LocalDateTime refreshExpiresAt;
    public java.time.LocalDateTime getRefreshExpiresAt() { return refreshExpiresAt; } public void setRefreshExpiresAt(java.time.LocalDateTime value) { refreshExpiresAt = value; }
}
