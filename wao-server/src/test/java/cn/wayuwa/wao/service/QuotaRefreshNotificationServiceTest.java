package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import cn.wayuwa.wao.domain.QuotaRefreshNotificationStateEntity;
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
    }

    @Test
    void remainingQuotaIncreaseSendsOneCardAndAdvancesBaseline() {
        QuotaRefreshNotificationStateEntity baseline = baseline(20);
        when(mapper.selectForUpdate("runner-account:wage@example.com")).thenReturn(baseline);
        when(notificationSettings.allows(any())).thenReturn(true);
        when(notifier.notifyCard(any())).thenReturn(true);

        service.observeRunner("rn1", report(0));

        ArgumentCaptor<FeishuWebhookNotifier.Card> card = ArgumentCaptor.forClass(FeishuWebhookNotifier.Card.class);
        verify(notifier).notifyCard(card.capture());
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
        when(notifier.notifyCard(any())).thenReturn(false);

        service.observeRunner("rn1", report(0));

        verify(notifier).notifyCard(any());
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
        verify(mapper).updateById(baseline);
        assertThat(baseline.getSnapshotJson()).contains("\"remainingPercent\":100");
    }

    @Test
    void quotaDecreaseUpdatesBaselineWithoutNotification() {
        QuotaRefreshNotificationStateEntity baseline = baseline(0);
        when(mapper.selectForUpdate("runner-account:wage@example.com")).thenReturn(baseline);

        service.observeRunner("rn1", report(20));

        verify(notifier, never()).notifyCard(any());
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

    private RunnerCodexQuotaReport report(int usedPercent) {
        return new RunnerCodexQuotaReport("wage@example.com", "pro",
                new CodexQuotaCreditsDto(false, false, "0"),
                List.of(new CodexQuotaLimitDto("codex", "通用使用限额",
                        List.of(new CodexQuotaWindowDto(usedPercent, 10_080L, 1_788_192_000L)))));
    }
}
