package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.ForbiddenException;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.domain.PersonalPrincipleEntity;
import cn.wayuwa.wao.domain.PersonalPrincipleTargetEntity;
import cn.wayuwa.wao.dto.SavePersonalPrincipleRequest;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.PersonalPrincipleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class PersonalPrincipleServiceTest {
    private final PersonalPrincipleMapper rules = mock(PersonalPrincipleMapper.class);
    private final MemberMapper members = mock(MemberMapper.class);
    private final PersonalPrincipleService service = new PersonalPrincipleService(rules, members, new ObjectMapper());

    @Test
    void screenTimeWithoutSourceRemainsUnjudged() {
        PersonalPrincipleEntity rule = rule("SCREEN_TIME");
        when(rules.visible("wg")).thenReturn(List.of(rule));
        when(rules.targets("r1")).thenReturn(List.of(target("wg", "ACCEPTED")));

        var projected = service.list("wg", LocalDate.now()).get(0);

        assertThat(projected.judgment()).isEqualTo("WAITING_DATA");
        assertThat(projected.judgmentReason()).contains("尚未接入");
        assertThat(projected.myCheckin()).isNull();
    }

    @Test
    void unrecordedManualRuleIsDueNotViolation() {
        PersonalPrincipleEntity rule = rule("MANUAL");
        rule.setReminderTime(LocalTime.of(0, 0));
        when(rules.visible("wg")).thenReturn(List.of(rule));
        when(rules.targets("r1")).thenReturn(List.of(target("wg", "ACCEPTED")));

        var projected = service.list("wg", LocalDate.now()).get(0);

        assertThat(projected.judgment()).isEqualTo("RECORD_DUE");
        assertThat(projected.judgmentReason()).contains("不据此判定违规");
    }

    @Test
    void anotherMemberCannotWriteMyCheckinOrConsent() {
        when(rules.selectById("r1")).thenReturn(rule("MANUAL"));
        when(rules.target("r1", "wd")).thenReturn(null);

        assertThatThrownBy(() -> service.checkin("r1", "wd", LocalDate.now(), "DONE", ""))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.consent("r1", "wd", "ACCEPTED"))
                .isInstanceOf(ForbiddenException.class);
        verify(rules, never()).saveCheckin(any());
    }

    @Test
    void staleEditCannotOverwriteCurrentVersion() {
        when(rules.selectById("r1")).thenReturn(rule("MANUAL"));
        SavePersonalPrincipleRequest request = new SavePersonalPrincipleRequest(
                "早点睡", "MUST", List.of("wg"), "每天晚上", "去睡觉", "", null, "MANUAL", false, 0);

        assertThatThrownBy(() -> service.update("r1", "wg", request)).isInstanceOf(ConflictException.class);
        verify(rules, never()).updateVersioned(any(), any(Integer.class));
    }

    @Test
    void adviceNeverProducesComplianceJudgment() {
        PersonalPrincipleEntity rule = rule("MANUAL");
        rule.setCategory("ADVICE");
        rule.setReminderTime(LocalTime.of(0, 0));
        when(rules.visible("wg")).thenReturn(List.of(rule));
        when(rules.targets("r1")).thenReturn(List.of(target("wg", "ACCEPTED")));

        assertThat(service.list("wg", LocalDate.now()).get(0).judgment()).isEqualTo("ADVICE");
    }

    private PersonalPrincipleEntity rule(String source) {
        PersonalPrincipleEntity value = new PersonalPrincipleEntity();
        value.setId("r1"); value.setOwnerMemberId("wg"); value.setTitle("不在床上玩手机");
        value.setCategory("MUST"); value.setTriggerText("每天晚上"); value.setDesiredAction("离开床");
        value.setExceptionText(""); value.setEvidenceSource(source); value.setPaused(false);
        value.setVersion(1); value.setUpdatedAt(LocalDateTime.now());
        return value;
    }

    private PersonalPrincipleTargetEntity target(String memberId, String consent) {
        PersonalPrincipleTargetEntity value = new PersonalPrincipleTargetEntity();
        value.setPrincipleId("r1"); value.setMemberId(memberId); value.setConsent(consent);
        return value;
    }
}
