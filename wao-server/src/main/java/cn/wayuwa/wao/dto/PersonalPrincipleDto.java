package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/** 当前成员可见的准则，以及仅属于自己的当日记录和判断。 */
public record PersonalPrincipleDto(
        String id, String ownerMemberId, String title, String category,
        String triggerText, String desiredAction, String exceptionText,
        LocalTime reminderTime, String evidenceSource, boolean paused, int version,
        LocalDateTime updatedAt, List<Target> targets, String myConsent,
        LocalDate selectedDate, Checkin myCheckin, String judgment, String judgmentReason) {
    public record Target(String memberId, String consent) { }
    public record Checkin(String status, String note, int principleVersion, LocalDateTime updatedAt) { }
}
