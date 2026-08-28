package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.dto.NotificationSettingsDto.PersonalPolicy;
import cn.wayuwa.wao.dto.NotificationSettingsDto.SystemPolicy;
import cn.wayuwa.wao.dto.SaveNotificationSettingsRequest;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class NotificationSettingsServiceTest {
    private final SettingService settings = mock(SettingService.class);
    private final MemberPreferenceMapper preferences = mock(MemberPreferenceMapper.class);
    private final MemberMapper members = mock(MemberMapper.class);
    private final NotificationSettingsService service = new NotificationSettingsService(
            settings, preferences, members, new ObjectMapper());

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
        SystemPolicy system = new SystemPolicy(true, true, true, true, true, true, true);

        assertThatThrownBy(() -> service.save("wd", new SaveNotificationSettingsRequest(system, null)))
                .isInstanceOf(UnauthorizedException.class).hasMessageContaining("管理员");
        verify(settings, never()).put(eq(SettingService.NOTIFICATION_POLICY), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void administratorCanUpdateSharedPolicyWithoutTouchingOtherMembers() {
        when(members.selectById("wg")).thenReturn(member("wg", "管理员"));
        SystemPolicy system = new SystemPolicy(true, false, false, false, true, true, false);

        service.save("wg", new SaveNotificationSettingsRequest(system, null));

        verify(settings).put(eq(SettingService.NOTIFICATION_POLICY), org.mockito.ArgumentMatchers.contains("aiRunFailure"));
        verify(preferences, never()).upsert(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static MemberEntity member(String id, String role) {
        MemberEntity member = new MemberEntity();
        member.setId(id);
        member.setRole(role);
        return member;
    }
}
