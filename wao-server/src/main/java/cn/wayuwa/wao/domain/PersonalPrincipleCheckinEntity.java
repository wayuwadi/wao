package cn.wayuwa.wao.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 成员亲自提交的当日记录；不会从联网状态推断。 */
public class PersonalPrincipleCheckinEntity {
    private String principleId;
    private String memberId;
    private LocalDate checkinDate;
    private String status;
    private String note;
    private Integer principleVersion;
    private LocalDateTime updatedAt;
    public String getPrincipleId() { return principleId; }
    public void setPrincipleId(String value) { principleId = value; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String value) { memberId = value; }
    public LocalDate getCheckinDate() { return checkinDate; }
    public void setCheckinDate(LocalDate value) { checkinDate = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getNote() { return note; }
    public void setNote(String value) { note = value; }
    public Integer getPrincipleVersion() { return principleVersion; }
    public void setPrincipleVersion(Integer value) { principleVersion = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
