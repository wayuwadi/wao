package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 密钥库分组表实体。 */
@TableName("vault_group")
public class VaultGroupEntity {

    @TableId
    private String id;
    private String name;
    private Integer ord;
    /** 归属成员 id：每个人只看得到自己的分组（见 ADR-024）。 */
    private String ownerMemberId;
    /** 是否该成员的默认分组：改名后依然成立，不再靠名字识别（见 ADR-036）。 */
    @TableField("is_default")
    private Boolean isDefault;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getOrd() { return ord; }
    public void setOrd(Integer ord) { this.ord = ord; }
    public String getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(String ownerMemberId) { this.ownerMemberId = ownerMemberId; }
    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
}
