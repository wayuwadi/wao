package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import cn.wayuwa.wao.domain.QuotaRefreshNotificationStateEntity;
import cn.wayuwa.wao.dto.AgyQuotaBucketDto;
import cn.wayuwa.wao.dto.AgyQuotaGroupDto;
import cn.wayuwa.wao.dto.AgyQuotaReport;
import cn.wayuwa.wao.dto.CodexQuotaCreditsDto;
import cn.wayuwa.wao.dto.CodexQuotaLimitDto;
import cn.wayuwa.wao.dto.CodexQuotaWindowDto;
import cn.wayuwa.wao.dto.RunnerCodexQuotaReport;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.QuotaRefreshNotificationStateMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuotaRefreshNotificationServiceTest {

    private final QuotaRefreshNotificationStateMapper mapper = mock(QuotaRefreshNotificationStateMapper.class);
    private final FeishuWebhookNotifier notifier = mock(FeishuWebhookNotifier.class);
    private final NotificationSettingsService notificationSettings = mock(NotificationSettingsService.class);
    private final QuotaRefreshNotificationService service = new QuotaRefreshNotificationService(
            mapper, notifier, notificationSettings, new ObjectMapper(), "https://wao.example/");

    @Test
    void resetCardIncreaseUsesCountAboveOneHundredAndSharedAccountBaseline() {
        var resetState = new QuotaRefreshNotificationStateEntity();
        resetState.setSnapshotJson("[{\"key\":\"reset-cards\",\"label\":\"可用重置卡\",\"remainingPercent\":123,\"resetsAt\":null}]");
        when(mapper.selectForUpdate("runner-account:wage@example.com:reset-cards")).thenReturn(resetState);
        when(notificationSettings.allows(any())).thenReturn(true);
        when(notifier.notifyResetCardIncrease(any())).thenReturn(true);
        var base = report(20);
        var current = new RunnerCodexQuotaReport(base.accountEmail(), base.plan(), base.credits(), base.limits(),
                new cn.wayuwa.wao.dto.CodexResetCreditsDto(125, List.of()));
        service.observeRunner("rn1", current);
        service.observeRunner("rn2", current);
        var card = ArgumentCaptor.forClass(FeishuWebhookNotifier.Card.class);
        verify(notifier).notifyResetCardIncrease(card.capture());
        verify(notifier, never()).notifyCard(any());
        verify(notifier, never()).notifyCodexQuotaRefresh(any());
        assertThat(card.getValue().title()).isEqualTo("Codex 重置卡已增加");
        assertThat(card.getValue().body()).contains("123 张 → 125 张").doesNotContain("125%");
    }

    @Test
    void firstRealSnapshotOnlyCreatesBaseline() {
        service.observeRunner("rn1", report(20));

        ArgumentCaptor<QuotaRefreshNotificationStateEntity> saved =
                ArgumentCaptor.forClass(QuotaRefreshNotificationStateEntity.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getSourceKey()).isEqualTo("runner-account:wage@example.com");
        assertThat(saved.getValue().getSnapshotJson())
                .contains("\"remainingPercent\":80")
                .doesNotContain("token");
        verify(notifier, never()).notifyCard(any());
        verify(notifier, never()).notifyCodexQuotaRefresh(any());
    }

    @Test
    void failedResetCardDeliveryRetriesUntilFeishuConfirmsAndThenDeduplicates() {
        var state = new QuotaRefreshNotificationStateEntity();
        state.setSnapshotJson("[{\"key\":\"reset-cards\",\"label\":\"可用重置卡\",\"remainingPercent\":2,\"resetsAt\":null}]");
        when(mapper.selectForUpdate("runner-account:wage@example.com:reset-cards")).thenReturn(state);
        when(notificationSettings.allows(any())).thenReturn(true);
        when(notifier.notifyResetCardIncrease(any())).thenReturn(false, true);
        var base = report(20);
        var current = new RunnerCodexQuotaReport(base.accountEmail(), base.plan(), base.credits(), base.limits(),
                new cn.wayuwa.wao.dto.CodexResetCreditsDto(3, List.of()));
        service.observeRunner("rn1", current);
        assertThat(state.getSnapshotJson()).contains("\"remainingPercent\":2");
        service.observeRunner("rn2", current);
        assertThat(state.getSnapshotJson()).contains("\"remainingPercent\":3");
        service.observeRunner("rn1", current);
        verify(notifier, org.mockito.Mockito.times(2)).notifyResetCardIncrease(any());
        verify(notifier, never()).notifyCard(any());
        verify(notifier, never()).notifyCodexQuotaRefresh(any());
    }

    @Test
    void remainingQuotaIncreaseSendsOneCardAndAdvancesBaseline() {
        QuotaRefreshNotificationStateEntity baseline = baseline(20);
        when(mapper.selectForUpdate("runner-account:wage@example.com")).thenReturn(baseline);
        when(notificationSettings.allows(any())).thenReturn(true);
        when(notifier.notifyCodexQuotaRefresh(any())).thenReturn(true);

        service.observeRunner("rn1", report(0));

        ArgumentCaptor<FeishuWebhookNotifier.Card> card = ArgumentCaptor.forClass(FeishuWebhookNotifier.Card.class);
        verify(notifier).notifyCodexQuotaRefresh(card.capture());
        assertThat(card.getValue().title()).isEqualTo("AI 额度已刷新");
        assertThat(card.getValue().body()).contains("80% → 100%").contains("周额度");
        assertThat(card.getValue().linkUrl()).isEqualTo("https://wao.example");
        verify(mapper).updateById(baseline);
        assertThat(baseline.getSnapshotJson()).contains("\"remainingPercent\":100");
    }

    @Test
    void failedDeliveryKeepsOldBaselineForNextSampleRetry() {
        QuotaRefreshNotificationStateEntity baseline = baseline(20);
        String oldSnapshot = baseline.getSnapshotJson();
        when(mapper.selectForUpdate("runner-account:wage@example.com")).thenReturn(baseline);
        when(notificationSettings.allows(any())).thenReturn(true);
        when(notifier.notifyCodexQuotaRefresh(any())).thenReturn(false);

        service.observeRunner("rn1", report(0));

        verify(notifier).notifyCodexQuotaRefresh(any());
        verify(mapper, never()).updateById(any(QuotaRefreshNotificationStateEntity.class));
        assertThat(baseline.getSnapshotJson()).isEqualTo(oldSnapshot);
    }

    @Test
    void quietOrDisabledPolicyConsumesEventWithoutSendingLateNotification() {
        QuotaRefreshNotificationStateEntity baseline = baseline(20);
        when(mapper.selectForUpdate("runner-account:wage@example.com")).thenReturn(baseline);
        when(notificationSettings.allows(any())).thenReturn(false);

        service.observeRunner("rn1", report(0));

        verify(notifier, never()).notifyCard(any());
        verify(notifier, never()).notifyCodexQuotaRefresh(any());
        verify(mapper).updateById(baseline);
        assertThat(baseline.getSnapshotJson()).contains("\"remainingPercent\":100");
    }

    @Test
    void quotaDecreaseUpdatesBaselineWithoutNotification() {
        QuotaRefreshNotificationStateEntity baseline = baseline(0);
        when(mapper.selectForUpdate("runner-account:wage@example.com")).thenReturn(baseline);

        service.observeRunner("rn1", report(20));

        verify(notifier, never()).notifyCard(any());
        verify(notifier, never()).notifyCodexQuotaRefresh(any());
        verify(mapper).updateById(baseline);
        assertThat(baseline.getSnapshotJson()).contains("\"remainingPercent\":80");
    }

    private QuotaRefreshNotificationStateEntity baseline(int usedPercent) {
        when(mapper.selectForUpdate("runner-account:wage@example.com")).thenReturn(null);
        service.observeRunner("rn1", report(usedPercent));
        ArgumentCaptor<QuotaRefreshNotificationStateEntity> saved =
                ArgumentCaptor.forClass(QuotaRefreshNotificationStateEntity.class);
        verify(mapper).insert(saved.capture());
        org.mockito.Mockito.clearInvocations(mapper, notifier, notificationSettings);
        return saved.getValue();
    }

    @Test
    void resetCardsAreObservedEvenWhenQuotaWindowsAreMissing() {
        var state = new QuotaRefreshNotificationStateEntity();
        state.setSnapshotJson("[{\"key\":\"reset-cards\",\"label\":\"可用重置卡\",\"remainingPercent\":0,\"resetsAt\":null}]");
        when(mapper.selectForUpdate("runner-account:wage@example.com:reset-cards")).thenReturn(state);
        when(notificationSettings.allows(any())).thenReturn(true);
        when(notifier.notifyResetCardIncrease(any())).thenReturn(true);
        service.observeRunner("local:windows", new RunnerCodexQuotaReport(" WAGE@example.com ", "pro", null, null,
                new cn.wayuwa.wao.dto.CodexResetCreditsDto(1, List.of())));
        service.observeRunner("rn2", new RunnerCodexQuotaReport("wage@example.com", "pro", null, null,
                new cn.wayuwa.wao.dto.CodexResetCreditsDto(1, List.of())));
        verify(notifier).notifyResetCardIncrease(any());
        verify(notifier, never()).notifyCodexQuotaRefresh(any());
        assertThat(state.getSnapshotJson()).contains("\"remainingPercent\":1");
    }

    @Test
    void centralCodexUsesFeishuWhileOtherProvidersKeepStationDelivery() {
        service.observeCentral(List.of(central("codex", 20), central("claude", 20)), List.of());
        var saved = ArgumentCaptor.forClass(QuotaRefreshNotificationStateEntity.class);
        verify(mapper, org.mockito.Mockito.times(2)).insert(saved.capture());
        saved.getAllValues().forEach(state -> when(mapper.selectForUpdate(state.getSourceKey())).thenReturn(state));
        when(notificationSettings.allows(any())).thenReturn(true);
        when(notifier.notifyCodexQuotaRefresh(any())).thenReturn(true);
        when(notifier.notifyCard(any())).thenReturn(true);
        service.observeCentral(List.of(central("codex", 0), central("claude", 0)), List.of());
        service.observeCentral(List.of(central("codex", 0), central("claude", 0)), List.of());
        var codex = ArgumentCaptor.forClass(FeishuWebhookNotifier.Card.class);
        var claude = ArgumentCaptor.forClass(FeishuWebhookNotifier.Card.class);
        verify(notifier).notifyCodexQuotaRefresh(codex.capture());
        verify(notifier).notifyCard(claude.capture());
        assertThat(codex.getValue().fields()).contains(List.of("账号", "codex"));
        assertThat(claude.getValue().fields()).contains(List.of("账号", "claude"));
    }

    @Test
    void agyFirstSnapshotBuildsBaselineAndIncreaseSendsFeishuCardAcrossGroups() {
        service.observeAgy("local:mac", agyReport(79, 91, 60, 100));
        var saved = ArgumentCaptor.forClass(QuotaRefreshNotificationStateEntity.class);
        verify(mapper).insert(saved.capture());
        var state = saved.getValue();
        assertThat(state.getSourceKey()).isEqualTo("agy-account:agy");
        verify(notifier, never()).notifyAgyQuotaRefresh(any());

        when(mapper.selectForUpdate("agy-account:agy")).thenReturn(state);
        when(notificationSettings.allows(any())).thenReturn(true);
        when(notifier.notifyAgyQuotaRefresh(any())).thenReturn(true);

        service.observeAgy("report:win", agyReport(100, 91, 100, 100));
        service.observeAgy("local:mac", agyReport(100, 91, 100, 100));

        var card = ArgumentCaptor.forClass(FeishuWebhookNotifier.Card.class);
        verify(notifier).notifyAgyQuotaRefresh(card.capture());
        verify(notifier, never()).notifyCodexQuotaRefresh(any());
        verify(notifier, never()).notifyCard(any());
        assertThat(card.getValue().title()).isEqualTo("Agy 额度已刷新");
        assertThat(card.getValue().fields()).contains(List.of("账号", "Agy"));
        assertThat(card.getValue().body())
                .contains("Gemini · 周额度", "79% → 100%")
                .contains("Claude / GPT · 周额度", "60% → 100%")
                .doesNotContain("1970");
    }

    @Test
    void agyFailedFeishuDeliveryKeepsBaselineForRetry() {
        var state = new QuotaRefreshNotificationStateEntity();
        state.setSourceKey("agy-account:agy");
        state.setSnapshotJson("[{\"key\":\"gemini-5h\",\"label\":\"Gemini · 5 小时\",\"remainingPercent\":50,\"resetsAt\":null}]");
        when(mapper.selectForUpdate("agy-account:agy")).thenReturn(state);
        when(notificationSettings.allows(any())).thenReturn(true);
        when(notifier.notifyAgyQuotaRefresh(any())).thenReturn(false, true);

        service.observeAgy("local:mac", new AgyQuotaReport("Agy", List.of(
                new AgyQuotaGroupDto("Gemini Models", List.of(
                        new AgyQuotaBucketDto("gemini-5h", "Five Hour Limit Remaining", "5h", 100, null))))));
        assertThat(state.getSnapshotJson()).contains("\"remainingPercent\":50");

        service.observeAgy("local:mac", new AgyQuotaReport("Agy", List.of(
                new AgyQuotaGroupDto("Gemini Models", List.of(
                        new AgyQuotaBucketDto("gemini-5h", "Five Hour Limit Remaining", "5h", 100, null))))));
        verify(notifier, org.mockito.Mockito.times(2)).notifyAgyQuotaRefresh(any());
        assertThat(state.getSnapshotJson()).contains("\"remainingPercent\":100");
    }

    @Test
    void agyQuietPolicyConsumesEventWithoutSendingLateFeishuNotification() {
        var state = new QuotaRefreshNotificationStateEntity();
        state.setSourceKey("agy-account:agy");
        state.setSnapshotJson("[{\"key\":\"gemini-weekly\",\"label\":\"Gemini · 周额度\",\"remainingPercent\":40,\"resetsAt\":null}]");
        when(mapper.selectForUpdate("agy-account:agy")).thenReturn(state);
        when(notificationSettings.allows(any())).thenReturn(false);

        service.observeAgy("local:mac", agyReport(100, 100, 100, 100));

        verify(notifier, never()).notifyAgyQuotaRefresh(any());
        verify(mapper).updateById(state);
        assertThat(state.getSnapshotJson()).contains("\"remainingPercent\":100");
    }

    private AgyQuotaReport agyReport(int geminiWeekly, int gemini5h, int claudeWeekly, int claude5h) {
        return new AgyQuotaReport("Agy", List.of(
                new AgyQuotaGroupDto("Gemini Models", List.of(
                        new AgyQuotaBucketDto("gemini-weekly", "Weekly Limit Remaining", "weekly", geminiWeekly, 1_788_192_000L),
                        new AgyQuotaBucketDto("gemini-5h", "Five Hour Limit Remaining", "5h", gemini5h, 1_788_000_000L))),
                new AgyQuotaGroupDto("Claude and GPT models", List.of(
                        new AgyQuotaBucketDto("3p-weekly", "Weekly Limit Remaining", "weekly", claudeWeekly, null),
                        new AgyQuotaBucketDto("3p-5h", "Five Hour Limit Remaining", "5h", claude5h, null)))));
    }

    private cn.wayuwa.wao.dto.AiSessionQuotaDto central(String provider, int used) {
        return new cn.wayuwa.wao.dto.AiSessionQuotaDto(provider, provider, provider, "pro", used, 100 - used,
                null, null, "SYNCED", null);
    }

    private RunnerCodexQuotaReport report(int usedPercent) {
        return new RunnerCodexQuotaReport("wage@example.com", "pro",
                new CodexQuotaCreditsDto(false, false, "0"),
                List.of(new CodexQuotaLimitDto("codex", "通用使用限额",
                        List.of(new CodexQuotaWindowDto(usedPercent, 10_080L, 1_788_192_000L)))));
    }
}
