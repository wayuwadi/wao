package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.dto.NotificationSettingsDto.PersonalPolicy;
import cn.wayuwa.wao.dto.NotificationSettingsDto.SystemPolicy;
import cn.wayuwa.wao.dto.SaveNotificationSettingsRequest;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class NotificationSettingsServiceTest {
    private final SettingService settings = mock(SettingService.class);
    private final MemberPreferenceMapper preferences = mock(MemberPreferenceMapper.class);
    private final MemberMapper members = mock(MemberMapper.class);
    private final NotificationSettingsService service = new NotificationSettingsService(
            settings, preferences, members, new ObjectMapper());

    @Test
    void jenkinsSuccessRespectsGlobalPolicyButNotFailureToggle() {
        when(settings.get(SettingService.NOTIFICATION_POLICY, "")).thenReturn(
                "{\"enabled\":true,\"deliveryFailure\":false,\"quietHoursEnabled\":false}");
        assertThat(service.deliverySuccessDecision().allowed()).isTrue();
        assertThat(service.deliveryFailureDecision().allowed()).isFalse();
        when(settings.get(SettingService.NOTIFICATION_POLICY, "")).thenReturn("{\"enabled\":false}");
        assertThat(service.deliverySuccessDecision().allowed()).isFalse();
        when(settings.get(SettingService.NOTIFICATION_POLICY, "")).thenReturn(
                "{\"enabled\":true,\"quietHoursEnabled\":true,\"quietStart\":\"00:00\",\"quietEnd\":\"23:59\"}");
        assertThat(service.deliverySuccessDecision(LocalDateTime.of(2026, 9, 19, 12, 0)).allowed()).isFalse();
    }

    @Test
    void defaultsKeepSuccessQuietAndFailuresManualActionsEnabled() {
        MemberEntity member = member("wd", "成员");
        when(members.selectById("wd")).thenReturn(member);

        var result = service.get("wd");

        assertThat(result.canManageSystem()).isFalse();
        assertThat(result.system().taskActivity()).isFalse();
        assertThat(result.system().taskSuccess()).isFalse();
        assertThat(result.system().aiRunSuccess()).isFalse();
        assertThat(result.system().aiRunFailure()).isTrue();
        assertThat(result.system().manualTask()).isTrue();
        assertThat(result.system().reminders()).isTrue();
        assertThat(result.system().deliveryFailure()).isTrue();
        assertThat(result.system().quietHoursEnabled()).isFalse();
        assertThat(result.personal().enabled()).isTrue();
    }

    @Test
    void memberCanOnlySaveTheirOwnPersonalPolicy() {
        when(members.selectById("wd")).thenReturn(member("wd", "成员"));
        PersonalPolicy personal = new PersonalPolicy(true, true, false, false, true, false);

        service.save("wd", new SaveNotificationSettingsRequest(null, personal));

        verify(preferences).upsert(eq("wd"), eq(NotificationSettingsService.PERSONAL_POLICY_KEY),
                eq("{\"enabled\":true,\"taskCreated\":true,\"taskCompleted\":false,"
                        + "\"aiRunSuccess\":false,\"aiRunFailure\":true,\"loginActivity\":false}"));
        verify(settings, never()).put(eq(SettingService.NOTIFICATION_POLICY), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nonAdminCannotOverwriteSharedSystemPolicy() {
        when(members.selectById("wd")).thenReturn(member("wd", "成员"));
        SystemPolicy system = new SystemPolicy(true, false, "22:00", "08:00",
                true, true, true, true, true, true, true, true);

        assertThatThrownBy(() -> service.save("wd", new SaveNotificationSettingsRequest(system, null)))
                .isInstanceOf(UnauthorizedException.class).hasMessageContaining("管理员");
        verify(settings, never()).put(eq(SettingService.NOTIFICATION_POLICY), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void administratorCanUpdateSharedPolicyWithoutTouchingOtherMembers() {
        when(members.selectById("wg")).thenReturn(member("wg", "管理员"));
        SystemPolicy system = new SystemPolicy(true, true, "22:00", "08:00",
                false, false, false, true, true, true, true, false);

        service.save("wg", new SaveNotificationSettingsRequest(system, null));

        verify(settings).put(eq(SettingService.NOTIFICATION_POLICY), org.mockito.ArgumentMatchers.contains("aiRunFailure"));
        verify(preferences, never()).upsert(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void readsLegacyPolicyWithSafeNewDefaultsAndBlocksAcrossMidnightQuietHours() {
        when(settings.get(SettingService.NOTIFICATION_POLICY, "")).thenReturn("""
                {"enabled":true,"quietHoursEnabled":true,"quietStart":"22:00","quietEnd":"08:00",
                 "taskActivity":false,"aiRunFailure":true}
                """);

        SystemPolicy policy = service.systemPolicy();

        assertThat(policy.reminders()).isTrue();
        assertThat(policy.deliveryFailure()).isTrue();
        assertThat(service.allows(SystemPolicy::reminders,
                LocalDateTime.of(2026, 8, 30, 23, 0))).isFalse();
        assertThat(service.allows(SystemPolicy::reminders,
                LocalDateTime.of(2026, 8, 31, 7, 59))).isFalse();
        assertThat(service.allows(SystemPolicy::reminders,
                LocalDateTime.of(2026, 8, 31, 8, 0))).isTrue();
    }

    @Test
    void rejectsAnAllDayAmbiguousQuietWindow() {
        when(members.selectById("wg")).thenReturn(member("wg", "管理员"));
        SystemPolicy system = new SystemPolicy(true, true, "08:00", "08:00",
                false, false, false, true, true, true, true, true);

        assertThatThrownBy(() -> service.save("wg", new SaveNotificationSettingsRequest(system, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("不能相同");
    }

    private static MemberEntity member(String id, String role) {
        MemberEntity member = new MemberEntity();
        member.setId(id);
        member.setRole(role);
        return member;
    }
}
