package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 一次成功进入 WAO 会话的最小审计事实，不保存网络或外部身份信息。 */
@TableName("account_login_event")
public class AccountLoginEventEntity {
    @TableId
    private String id;
    private String accountId;
    private String memberId;
    private String provider;
    private LocalDateTime loggedInAt;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String value) { accountId = value; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String value) { memberId = value; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public LocalDateTime getLoggedInAt() { return loggedInAt; }
    public void setLoggedInAt(LocalDateTime value) { loggedInAt = value; }
}
