package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.SystemSettingEntity;
import cn.wayuwa.wao.dto.AgyQuotaBucketDto;
import cn.wayuwa.wao.dto.AgyQuotaGroupDto;
import cn.wayuwa.wao.dto.AgyQuotaReport;
import cn.wayuwa.wao.mapper.SystemSettingMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgyQuotaServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final SystemSettingMapper settings = mock(SystemSettingMapper.class);
    private final QuotaRefreshNotificationService notifications = mock(QuotaRefreshNotificationService.class);

    private AgyQuotaReport report(int geminiWeeklyRemaining) {
        return new AgyQuotaReport("Agy", List.of(
                new AgyQuotaGroupDto("Gemini Models", List.of(
                        new AgyQuotaBucketDto("gemini-weekly", "Weekly Limit Remaining", "weekly",
                                geminiWeeklyRemaining, 1_900_000_000L),
                        new AgyQuotaBucketDto("gemini-5h", "Five Hour Limit Remaining", "5h",
                                92, 1_900_003_600L))),
                new AgyQuotaGroupDto("Claude and GPT models", List.of(
                        new AgyQuotaBucketDto("3p-weekly", "Weekly Limit Remaining", "weekly", 100, null)))));
    }

    private SystemSettingEntity row(String source, int ageSeconds, boolean failed, int remaining) throws Exception {
        var entity = new SystemSettingEntity();
        entity.setCfgKey(AgyQuotaService.PREFIX + source);
        entity.setCfgValue(json.writeValueAsString(new AgyQuotaService.Snapshot(
                source, Instant.now().minusSeconds(ageSeconds).toString(), failed, report(remaining))));
        return entity;
    }

    @Test
    void sharedDatabaseMergesNewestByAccountWithoutSumming() throws Exception {
        when(settings.selectList(any())).thenReturn(List.of(row("macbook", 60, false, 70), row("windows1", 10, false, 85)));
        var service = spy(new AgyQuotaService(settings, json, notifications, false, "unused", "local", "Agy"));
        var accounts = service.listAccounts();
        verify(service, never()).readLocal();
        verifyNoInteractions(notifications);
        assertThat(accounts).singleElement().satisfies(account -> {
            assertThat(account.sources()).containsExactly("macbook", "windows1");
            assertThat(account.groups().get(0).buckets().get(0).remainingPercent()).isEqualTo(85);
            assertThat(account.fresh()).isTrue();
        });
    }

    @Test
    void failedLocalReadRetainsOldSnapshotMarksStaleAndThrottles() throws Exception {
        var old = row("local", 60, false, 75);
        String originalTime = json.readTree(old.getCfgValue()).path("observedAt").asText();
        when(settings.selectById(any())).thenReturn(old);
        when(settings.selectList(any())).thenReturn(List.of(old));
        var service = spy(new AgyQuotaService(settings, json, notifications, true, "unused", "local", "Agy"));
        doThrow(new IllegalStateException("secret-token-should-not-leak")).when(service).readLocal();
        assertThat(service.listAccounts().get(0).fresh()).isFalse();
        service.listAccounts();
        verify(service, times(1)).readLocal();
        verify(settings).updateById(old);
        assertThat(old.getCfgValue()).contains(originalTime).doesNotContain("secret-token");
        verifyNoInteractions(notifications);
    }

    @Test
    void successfulLocalReadPersistsAndNotifies() throws Exception {
        var stored = new ArrayList<SystemSettingEntity>();
        when(settings.insert(any(SystemSettingEntity.class))).thenAnswer(inv -> {
            stored.add(inv.getArgument(0));
            return 1;
        });
        when(settings.selectList(any())).thenAnswer(inv -> stored);
        var service = spy(new AgyQuotaService(settings, json, notifications, true, "unused", "local", "Agy"));
        doReturn(report(79)).when(service).readLocal();
        assertThat(service.listAccounts()).singleElement().satisfies(account -> {
            assertThat(account.fresh()).isTrue();
            assertThat(account.accountLabel()).isEqualTo("Agy");
        });
        verify(notifications).observeAgy("local:local", report(79));
    }

    @Test
    void remoteReportStoresSnapshotAndNotifies() {
        var stored = new ArrayList<SystemSettingEntity>();
        when(settings.insert(any(SystemSettingEntity.class))).thenAnswer(inv -> {
            stored.add(inv.getArgument(0));
            return 1;
        });
        var service = new AgyQuotaService(settings, json, notifications, false, "unused", "local", "Agy");
        var dto = service.report("mac-mini", report(88), Instant.parse("2026-11-25T03:00:00Z"));
        assertThat(dto.sources()).containsExactly("mac-mini");
        assertThat(dto.fresh()).isTrue();
        assertThat(stored).singleElement().satisfies(row -> {
            assertThat(row.getCfgKey()).isEqualTo(AgyQuotaService.PREFIX + "mac-mini");
        });
        verify(notifications).observeAgy("report:mac-mini", report(88));
    }

    @Test
    void notificationFailureDoesNotFailReportOrLocalRead() {
        when(settings.insert(any(SystemSettingEntity.class))).thenReturn(1);
        doThrow(new IllegalStateException("feishu down")).when(notifications).observeAgy(any(), any());
        var service = new AgyQuotaService(settings, json, notifications, false, "unused", "local", "Agy");
        var dto = service.report("mac-mini", report(90), Instant.now());
        assertThat(dto.accountLabel()).isEqualTo("Agy");
    }

    @Test
    void rejectsInvalidSourceOrEmptyReport() {
        var service = new AgyQuotaService(settings, json, notifications, false, "unused", "local", "Agy");
        assertThatThrownBy(() -> service.report("bad source", report(50), Instant.now()))
                .hasMessageContaining("Agy 额度来源");
        assertThatThrownBy(() -> service.report("mac", new AgyQuotaReport("Agy", List.of()), Instant.now()))
                .hasMessageContaining("至少需要一个有效");
    }
}
