package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** CalDAV 账户下的一个订阅日历。 */
@TableName("caldav_calendar")
public class CaldavCalendarEntity {

    @TableId
    private String id;
    private String accountId;
    /** 日历集合在服务器上的绝对地址。 */
    private String href;
    private String name;
    private String color;
    private Boolean enabled;
    private Integer ord;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getHref() { return href; }
    public void setHref(String href) { this.href = href; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getOrd() { return ord; }
    public void setOrd(Integer ord) { this.ord = ord; }
}
