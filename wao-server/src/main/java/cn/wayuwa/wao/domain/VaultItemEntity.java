package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 密钥库条目表实体。secret 以 AES-256-GCM 密文 + nonce 存储，服务层不做整体反序列化，按需解密。 */
@TableName("vault_item")
public class VaultItemEntity {

    @TableId
    private String id;
    private String groupId;
    private String name;
    private String icon;
    private String userName;
    private String site;
    private String note;
    private byte[] secretCiphertext;
    private byte[] secretNonce;
    private String updatedLabel;
    private Integer ord;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getSite() { return site; }
    public void setSite(String site) { this.site = site; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public byte[] getSecretCiphertext() { return secretCiphertext; }
    public void setSecretCiphertext(byte[] secretCiphertext) { this.secretCiphertext = secretCiphertext; }
    public byte[] getSecretNonce() { return secretNonce; }
    public void setSecretNonce(byte[] secretNonce) { this.secretNonce = secretNonce; }
    public String getUpdatedLabel() { return updatedLabel; }
    public void setUpdatedLabel(String updatedLabel) { this.updatedLabel = updatedLabel; }
    public Integer getOrd() { return ord; }
    public void setOrd(Integer ord) { this.ord = ord; }
}
