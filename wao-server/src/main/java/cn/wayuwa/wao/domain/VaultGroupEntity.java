package cn.wayuwa.wao.domain;

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

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getOrd() { return ord; }
    public void setOrd(Integer ord) { this.ord = ord; }
    public String getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(String ownerMemberId) { this.ownerMemberId = ownerMemberId; }
}
