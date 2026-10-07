package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.wayuwa.wao.domain.*;
import cn.wayuwa.wao.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FeishuBotActivityServiceTest {
    private final FeishuWebhookConfigMapper bots = mock(FeishuWebhookConfigMapper.class);
    private final FeishuQuickCollectEventMapper captures = mock(FeishuQuickCollectEventMapper.class);
    private final FeishuBotSendMapper sends = mock(FeishuBotSendMapper.class);
    private final FeishuBotUsageMapper references = mock(FeishuBotUsageMapper.class);
    private final SettingService settings = mock(SettingService.class);
    private final FeishuBotInboxMapper inbox = mock(FeishuBotInboxMapper.class);
    private final FeishuBotActivityService service = new FeishuBotActivityService(bots, captures, sends,
            references, settings, new ObjectMapper(), inbox);

    @Test
    void referencesUseStableIdsAndReceiverReadinessDoesNotCreateVerificationEvidence() {
        var bot = new FeishuWebhookConfigEntity(); bot.setId("fw1"); bot.setDefaultMarker("SYSTEM"); bot.setCaptureKind("TASK");
        when(bots.selectById("fw1")).thenReturn(bot);
        when(references.workdayPreferences()).thenReturn(List.of(
                "{\"energyNotification\":{\"channel\":\"WEBHOOK\",\"webhookId\":\"fw1\",\"webhookName\":\"旧名称\"}}",
                "{\"energyNotification\":{\"channel\":\"WEBHOOK\",\"webhookId\":\"fw2\",\"webhookName\":\"fw1\"}}"));
        when(settings.get(SettingService.QUICK_COLLECT_VERIFY_TOKEN)).thenReturn("private-token");
        var result = service.activity("fw1");
        assertThat(result.canDelete()).isFalse();
        assertThat(result.usages()).anySatisfy(usage -> {
            assertThat(usage.kind()).isEqualTo("SAVED_PREFERENCE"); assertThat(usage.count()).isEqualTo(1);
        });
        assertThat(result.receiver().callbackVerified()).isTrue();
        assertThat(result.recentCaptures()).isEmpty();
        assertThat(result.toString()).doesNotContain("private-token").doesNotContain("旧名称");
    }

    @Test
    void latestInboxFailureOverridesOlderSuccessfulCaptureWithoutLeakingContent() {
        var bot = new FeishuWebhookConfigEntity(); bot.setId("fw1");
        when(bots.selectById("fw1")).thenReturn(bot);
        var created = new FeishuQuickCollectEventEntity();
        created.setMessageId("om1"); created.setStatus("CREATED"); created.setCaptureKind("NOTE");
        created.setTargetId("note1"); created.setUpdatedAt(LocalDateTime.of(2026,10,7,8,0));
        created.setErrorMessage("upstream https://example.invalid/secret-value");
        when(captures.selectList(any())).thenReturn(List.of(created));
        var failure = new FeishuBotInboxEntity(); failure.setMessageId("om1"); failure.setCaptureKind("NOTE");
        failure.setStatus("FAILED"); failure.setErrorCode("CAPTURE_FAILED"); failure.setUpdatedAt(created.getUpdatedAt().plusMinutes(1));
        when(inbox.selectList(any())).thenReturn(List.of(failure));
        var result = service.activity("fw1");
        assertThat(result.recentCaptures()).singleElement().satisfies(capture -> {
            assertThat(capture.status()).isEqualTo("FAILED"); assertThat(capture.errorCode()).isEqualTo("CAPTURE_FAILED");
        });
        assertThat(result.toString()).doesNotContain("secret-value");
    }

    @Test
    void testAndSendResultsContainOnlyStatusAndSafeErrorCode() {
        service.recordSend("fw1", "TEST", true); service.recordSend("fw1", "SEND", false);
        var stored = ArgumentCaptor.forClass(FeishuBotSendEntity.class);
        verify(sends, times(2)).insert(stored.capture());
        assertThat(stored.getAllValues().get(0).getKind()).isEqualTo("TEST");
        assertThat(stored.getAllValues().get(0).getStatus()).isEqualTo("SUCCESS");
        assertThat(stored.getAllValues().get(1).getErrorCode()).isEqualTo("DELIVERY_FAILED");
    }
}
