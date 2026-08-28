package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

/** 通知中心成员登录频率聚合行。 */
public class MemberLoginUsageRow {
    private String memberId;
    private String memberName;
    private String color;
    private long count7Days;
    private long count30Days;
    private LocalDateTime lastLoginAt;

    public String getMemberId() { return memberId; }
    public void setMemberId(String value) { memberId = value; }
    public String getMemberName() { return memberName; }
    public void setMemberName(String value) { memberName = value; }
    public String getColor() { return color; }
    public void setColor(String value) { color = value; }
    public long getCount7Days() { return count7Days; }
    public void setCount7Days(long value) { count7Days = value; }
    public long getCount30Days() { return count30Days; }
    public void setCount30Days(long value) { count30Days = value; }
    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(LocalDateTime value) { lastLoginAt = value; }
}
