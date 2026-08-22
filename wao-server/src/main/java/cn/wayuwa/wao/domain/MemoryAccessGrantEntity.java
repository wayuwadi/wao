package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 一枚设备令牌对一个记忆范围的最小授权。 */
@TableName("memory_access_grant")
public class MemoryAccessGrantEntity {
    @TableId private String id;
    private String tokenId;
    private String scopeType;
    private String scopeId;
    private Boolean canRead;
    private Boolean canPropose;
    private Boolean canDirectWrite;
    private LocalDateTime createdAt;
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTokenId() { return tokenId; }
    public void setTokenId(String tokenId) { this.tokenId = tokenId; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public String getScopeId() { return scopeId; }
    public void setScopeId(String scopeId) { this.scopeId = scopeId; }
    public Boolean getCanRead() { return canRead; }
    public void setCanRead(Boolean canRead) { this.canRead = canRead; }
    public Boolean getCanPropose() { return canPropose; }
    public void setCanPropose(Boolean canPropose) { this.canPropose = canPropose; }
    public Boolean getCanDirectWrite() { return canDirectWrite; }
    public void setCanDirectWrite(Boolean canDirectWrite) { this.canDirectWrite = canDirectWrite; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
