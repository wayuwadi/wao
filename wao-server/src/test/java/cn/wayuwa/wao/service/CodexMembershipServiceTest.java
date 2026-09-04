package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.domain.SystemSettingEntity;
import cn.wayuwa.wao.domain.QuotaRefreshNotificationStateEntity;
import cn.wayuwa.wao.dto.CodexQuotaAccountDto;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.SystemSettingMapper;
import cn.wayuwa.wao.mapper.QuotaRefreshNotificationStateMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CodexMembershipServiceTest {
    final SystemSettingMapper settings = mock(SystemSettingMapper.class);
    final QuotaRefreshNotificationStateMapper states = mock(QuotaRefreshNotificationStateMapper.class);
    final CodexQuotaService quotas = mock(CodexQuotaService.class);
    final NotificationSettingsService policy = mock(NotificationSettingsService.class);
    final FeishuWebhookNotifier notifier = mock(FeishuWebhookNotifier.class);
    final ObjectMapper json = new ObjectMapper();
    final CodexMembershipService service = new CodexMembershipService(settings, states, quotas, policy, notifier, json, "https://wao.example");
    final String account = "member@example.com";

    @Test void storesManualDatesWithBoundedKeyAndNewRevision() {
        when(quotas.listAccounts()).thenReturn(List.of(new CodexQuotaAccountDto(account, account, "pro", null, List.of(), List.of(), "2026-09-04", true)));
        var saved = service.save(new CodexMembershipService.Membership(account, "2026-01-01", "2026-12-01", ""));
        var captor = ArgumentCaptor.forClass(SystemSettingEntity.class);
        verify(settings).insert(captor.capture());
        assertThat(captor.getValue().getCfgKey()).hasSizeLessThanOrEqualTo(64);
        assertThat(saved.revision()).isNotBlank();
        assertThat(captor.getValue().getCfgValue()).contains("2026-12-01");
    }

    @Test void staleRevisionCannotOverwriteAnotherEditorsDates() throws Exception {
        when(quotas.listAccounts()).thenReturn(List.of(new CodexQuotaAccountDto(account, account, "pro", null, List.of(), List.of(), "2026-09-04", true)));
        var row = row("2026-09-07");
        when(settings.selectById(anyString())).thenReturn(row);
        assertThatThrownBy(() -> service.save(new CodexMembershipService.Membership(account, null, "2026-12-01", "stale"))).isInstanceOf(ConflictException.class);
        verify(settings, never()).update(any(SystemSettingEntity.class), any());
    }

    @Test void remindersStartThreeDaysBeforeAndSuccessfulDeliveryIsDeduplicated() throws Exception {
        when(policy.allows(any())).thenReturn(true);
        when(settings.selectList(any())).thenReturn(List.of(row("2026-09-07")));
        when(notifier.notifyCard(any())).thenReturn(true);
        service.sendDueReminders(LocalDate.of(2026, 9, 3));
        verify(notifier, never()).notifyCard(any());
        service.sendDueReminders(LocalDate.of(2026, 9, 4));
        var state = ArgumentCaptor.forClass(QuotaRefreshNotificationStateEntity.class);
        verify(states).insert(state.capture());
        assertThat(state.getValue().getSnapshotJson()).isEqualTo("sent");
        when(states.selectForUpdate(anyString())).thenReturn(state.getValue());
        service.sendDueReminders(LocalDate.of(2026, 9, 5));
        verify(notifier, times(1)).notifyCard(any());
    }

    @Test void failedDeliveryCanRetryAndQuietPolicyDoesNotSend() throws Exception {
        when(settings.selectList(any())).thenReturn(List.of(row("2026-09-07")));
        service.sendDueReminders(LocalDate.of(2026, 9, 4));
        verify(notifier, never()).notifyCard(any());
        when(policy.allows(any())).thenReturn(true);
        when(notifier.notifyCard(any())).thenReturn(false);
        service.sendDueReminders(LocalDate.of(2026, 9, 4));
        var state = ArgumentCaptor.forClass(QuotaRefreshNotificationStateEntity.class);
        verify(states).insert(state.capture());
        assertThat(state.getValue().getSnapshotJson()).isEqualTo("pending");
        when(states.selectForUpdate(anyString())).thenReturn(state.getValue());
        when(notifier.notifyCard(any())).thenReturn(true);
        service.sendDueReminders(LocalDate.of(2026, 9, 4));
        verify(notifier, times(2)).notifyCard(any());
        assertThat(state.getValue().getSnapshotJson()).isEqualTo("sent");
    }

    private SystemSettingEntity row(String expires) throws Exception {
        var row = new SystemSettingEntity(); row.setCfgValue(json.writeValueAsString(new CodexMembershipService.Membership(account, null, expires, "latest"))); return row;
    }
}
