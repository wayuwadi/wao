package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

/** 每位适用成员单独确认参与。 */
public class PersonalPrincipleTargetEntity {
    private String principleId;
    private String memberId;
    private String consent;
    private LocalDateTime updatedAt;
    public String getPrincipleId() { return principleId; }
    public void setPrincipleId(String value) { principleId = value; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String value) { memberId = value; }
    public String getConsent() { return consent; }
    public void setConsent(String value) { consent = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
