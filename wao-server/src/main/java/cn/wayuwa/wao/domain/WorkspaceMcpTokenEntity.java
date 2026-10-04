package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 工作区 MCP 设备令牌；只持久化不可逆摘要。 */
@TableName("workspace_mcp_token")
public class WorkspaceMcpTokenEntity {
    @TableId private String id;
    private String memberId;
    private String name;
    private String tokenPrefix;
    private String tokenHash;
    private Boolean canWrite;
    private Boolean canPublishHostedPage;
    private String permissionPolicy;
    private String clientType;
    private LocalDateTime createdAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime revokedAt;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String value) { memberId = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getTokenPrefix() { return tokenPrefix; }
    public void setTokenPrefix(String value) { tokenPrefix = value; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String value) { tokenHash = value; }
    public Boolean getCanWrite() { return canWrite; }
    public void setCanWrite(Boolean value) { canWrite = value; }
    public Boolean getCanPublishHostedPage() { return canPublishHostedPage; }
    public void setCanPublishHostedPage(Boolean value) { canPublishHostedPage = value; }
    public String getPermissionPolicy() { return permissionPolicy; }
    public void setPermissionPolicy(String value) { permissionPolicy = value; }
    public String getClientType() { return clientType; }
    public void setClientType(String value) { clientType = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime value) { lastUsedAt = value; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime value) { revokedAt = value; }
}
