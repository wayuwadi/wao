package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import cn.wayuwa.wao.domain.SystemSettingEntity;
import cn.wayuwa.wao.dto.*;
import cn.wayuwa.wao.mapper.SystemSettingMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalCodexQuotaServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final SystemSettingMapper settings = mock(SystemSettingMapper.class);
    private final QuotaRefreshNotificationService notifications = mock(QuotaRefreshNotificationService.class);
    private RunnerCodexQuotaReport report(int used) {
        return new RunnerCodexQuotaReport("user@example.com", "pro", null,
            List.of(new CodexQuotaLimitDto("codex", "Codex", List.of(new CodexQuotaWindowDto(used, 10080L, 1900000000L)))),
            new CodexResetCreditsDto(3, List.of()));
    }
    private SystemSettingEntity row(String source, int age, boolean failed, int used) throws Exception {
        var row = new SystemSettingEntity(); row.setCfgKey(LocalCodexQuotaService.PREFIX + source);
        row.setCfgValue(json.writeValueAsString(new LocalCodexQuotaService.Snapshot(source, Instant.now().minusSeconds(age).toString(), failed, report(used))));
        return row;
    }

    @Test void sharedDatabaseReusesNewestAccountWithoutSummingOrLocalLogin() throws Exception {
        when(settings.selectList(any())).thenReturn(List.of(row("windows1", 60, false, 40), row("linux2", 10, false, 42)));
        var service = spy(new LocalCodexQuotaService(settings, json, notifications, false, "unused", "cloud"));
        var accounts = service.merge(List.of());
        verify(service, never()).readLocal();
        verifyNoInteractions(notifications);
        assertThat(accounts).singleElement().satisfies(account -> {
            assertThat(account.runnerNames()).containsExactly("linux2", "windows1");
            assertThat(account.limits().get(0).windows().get(0).usedPercent()).isEqualTo(42);
            assertThat(account.resetCredits().availableCount()).isEqualTo(3);
            assertThat(account.fresh()).isTrue();
        });
    }

    @Test void failedReadRetainsSnapshotButMarksStaleAndThrottlesRetry() throws Exception {
        var old = row("windows1", 60, false, 42);
        String originalTime = json.readTree(old.getCfgValue()).path("observedAt").asText();
        when(settings.selectById(any())).thenReturn(old);
        when(settings.selectList(any())).thenReturn(List.of(old));
        var service = spy(new LocalCodexQuotaService(settings, json, notifications, true, "unused", "windows1"));
        doThrow(new IllegalStateException("private-secret-must-not-be-saved")).when(service).readLocal();
        assertThat(service.merge(List.of()).get(0).fresh()).isFalse();
        service.merge(List.of());
        verify(service, times(1)).readLocal();
        verify(settings).updateById(old);
        assertThat(old.getCfgValue()).contains(originalTime).doesNotContain("private-secret");
        verifyNoInteractions(notifications);
    }

    @Test void noLoginReportsUnavailableInsteadOfFakeZero() throws Exception {
        when(settings.selectList(any())).thenReturn(List.of());
        var service = spy(new LocalCodexQuotaService(settings, json, notifications, true, "unused", "windows1"));
        doThrow(new IllegalStateException()).when(service).readLocal();
        assertThatThrownBy(() -> service.merge(List.of())).hasMessageContaining("尚未读到本机 Codex 额度");
        verify(settings).insert(any(SystemSettingEntity.class));
        verifyNoInteractions(notifications);
    }

    @Test void successfulReadPersistsOnlyDisplayFieldsAndBecomesAvailable() throws Exception {
        var stored = new java.util.ArrayList<SystemSettingEntity>();
        when(settings.insert(any(SystemSettingEntity.class))).thenAnswer(invocation -> {
            stored.add(invocation.getArgument(0)); return 1;
        });
        when(settings.selectList(any())).thenAnswer(invocation -> stored);
        var service = spy(new LocalCodexQuotaService(settings, json, notifications, true, "unused", "windows1"));
        doReturn(report(42)).when(service).readLocal();
        assertThat(service.merge(List.of())).singleElement().satisfies(account -> {
            assertThat(account.fresh()).isTrue();
            assertThat(account.accountEmail()).isEqualTo("user@example.com");
        });
        assertThat(stored).singleElement().satisfies(row -> {
            assertThat(row.getCfgKey()).isEqualTo(LocalCodexQuotaService.PREFIX + "windows1");
            assertThat(row.getCfgValue()).contains("usedPercent", "availableCount").doesNotContain("executable", "token");
        });
        service.merge(List.of());
        verify(notifications).observeRunner("local:windows1", report(42));
        verify(service).readLocal();
    }

    @Test void notificationFailureKeepsSavedQuotaAvailable() throws Exception {
        var stored = new java.util.ArrayList<SystemSettingEntity>();
        when(settings.insert(any(SystemSettingEntity.class))).thenAnswer(invocation -> {
            stored.add(invocation.getArgument(0)); return 1;
        });
        when(settings.selectList(any())).thenAnswer(invocation -> stored);
        var service = spy(new LocalCodexQuotaService(settings, json, notifications, true, "unused", "windows1"));
        doReturn(report(42)).when(service).readLocal();
        doThrow(new IllegalStateException()).when(notifications).observeRunner(any(), any());
        assertThat(service.merge(List.of()).get(0).fresh()).isTrue();
        assertThat(stored.get(0).getCfgValue()).contains("\"failed\":false");
    }

    @Test void failedPersistenceDoesNotAnnounceUnsavedQuota() throws Exception {
        when(settings.insert(any(SystemSettingEntity.class))).thenThrow(new IllegalStateException());
        var service = spy(new LocalCodexQuotaService(settings, json, notifications, true, "unused", "windows1"));
        doReturn(report(42)).when(service).readLocal();
        assertThatThrownBy(service::refresh).hasMessageContaining("额度快照保存失败");
        verifyNoInteractions(notifications);
    }

    @Test void expiredSnapshotsAndFutureClockAreNotFresh() throws Exception {
        var service = new LocalCodexQuotaService(settings, json, notifications, false, "unused", "cloud");
        when(settings.selectList(any())).thenReturn(List.of(row("windows1", 901, false, 42)));
        assertThat(service.merge(List.of()).get(0).fresh()).isFalse();
        when(settings.selectList(any())).thenReturn(List.of(row("windows1", -120, false, 42)));
        assertThat(service.merge(List.of()).get(0).fresh()).isFalse();
    }
}
