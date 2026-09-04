package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.wayuwa.wao.domain.*;
import cn.wayuwa.wao.dto.*;
import cn.wayuwa.wao.feishu.*;
import cn.wayuwa.wao.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TianrenNotificationServiceTest {
    private final TianrenNotificationMapper deliveries = mock(TianrenNotificationMapper.class);
    private final TianrenDailyReadingMapper readingMapper = mock(TianrenDailyReadingMapper.class);
    private final TianrenReadingService readings = mock(TianrenReadingService.class);
    private final AiAssistantConversationService conversations = mock(AiAssistantConversationService.class);
    private final MemberPreferenceMapper preferences = mock(MemberPreferenceMapper.class);
    private final NotificationSettingsService policies = mock(NotificationSettingsService.class);
    private final SettingService settings = mock(SettingService.class);
    private final FeishuWebhookConfigService webhooks = mock(FeishuWebhookConfigService.class);
    private final FeishuWebhookNotifier webhookSender = mock(FeishuWebhookNotifier.class);
    private final FeishuOpenApiClient feishu = mock(FeishuOpenApiClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final TianrenNotificationService service = new TianrenNotificationService(deliveries, readingMapper,
            readings, conversations, preferences, policies, settings, webhooks, webhookSender, feishu, json);
    private final Map<String, TianrenNotificationEntity> store = new HashMap<>();
    private final TianrenDailyReadingEntity row = new TianrenDailyReadingEntity();
    private TianrenReadingDto reading;

    @BeforeEach
    void setup() {
        row.setId("tr-test"); row.setMemberId("member"); row.setConversationId("cv-test");
        row.setReadingDate(LocalDate.now(ZoneId.of("Asia/Shanghai")));
        var hex = new TianrenHexagramEngine().describe(List.of(7, 7, 7, 8, 8, 8));
        reading = new TianrenReadingDto(row.getId(), row.getReadingDate(), hex.lines(), hex.changingLines(),
                hex.primary(), hex.relating(), row.getConversationId(), LocalDateTime.now());
        when(readingMapper.selectById("tr-test")).thenReturn(row);
        when(readings.getOwned("member", "tr-test")).thenReturn(reading);
        when(readings.createShareToken("member", "tr-test")).thenReturn("public-token");
        when(conversations.latestAssistantReply("cv-test", "member")).thenReturn("真实解读\n今天完成一件小事。");
        when(policies.allowsReminders()).thenReturn(true);
        config(true, "oc-group");
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("test-app");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("test-only-secret");
        when(feishu.tenantAccessToken(anyString(), anyString())).thenReturn("test-only-token");
        when(feishu.sendRawCard(anyString(), anyString(), any(), anyString())).thenReturn("om-receipt");
        when(deliveries.candidates(any(), anyString())).thenAnswer(inv -> store.isEmpty() ? List.of(row) : List.of());
        when(deliveries.find(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        when(deliveries.insert(any())).thenAnswer(inv -> {
            TianrenNotificationEntity value = inv.getArgument(0);
            return store.putIfAbsent(value.readingId(), value) == null ? 1 : 0;
        });
        when(deliveries.pending()).thenAnswer(inv -> store.values().stream().filter(v -> v.state().equals("PENDING")).toList());
        when(deliveries.claim(anyString(), anyString(), any())).thenAnswer(inv ->
                deliveries.transition(inv.getArgument(0), "PENDING", "SENDING", "正在发送", null, inv.getArgument(2)));
        when(deliveries.finish(anyString(), anyString(), anyString(), anyString(), nullable(String.class), any()))
                .thenAnswer(inv -> deliveries.transition(inv.getArgument(0), "SENDING", inv.getArgument(2),
                        inv.getArgument(3), inv.getArgument(4), inv.getArgument(5)));
        when(deliveries.transition(anyString(), anyString(), anyString(), anyString(), nullable(String.class), any()))
                .thenAnswer(inv -> {
                    TianrenNotificationEntity value = store.get(inv.getArgument(0));
                    if (value == null || !value.state().equals(inv.getArgument(1))) return 0;
                    store.put(value.readingId(), new TianrenNotificationEntity(value.readingId(), inv.getArgument(2),
                            value.channel(), value.targetId(), value.targetName(), value.payload(), inv.getArgument(4),
                            inv.getArgument(3), inv.getArgument(5)));
                    return 1;
                });
    }

    @Test void sendsOnceFromPersistedReadingAndInterpretation() throws Exception {
        service.tick(); service.tick();
        var captured = ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(feishu).sendRawCard(eq("test-only-token"), eq("oc-group"), captured.capture(), eq("tianren-tr-test"));
        assertThat(captured.getValue().path("schema").asText()).isEqualTo("2.0");
        assertThat(captured.getValue().toString()).contains("泰卦", "真实解读", "public-token", "img_v3_").doesNotContain("谦尊而光");
        assertThat(service.status("member", "tr-test").state()).isEqualTo("SENT");
        service.retry("member", "tr-test", true); service.tick();
        verify(feishu, times(1)).sendRawCard(anyString(), anyString(), any(), anyString());
        verifyNoInteractions(webhookSender);
    }

    @Test void oldOrDisabledConfigurationDoesNotExposeOrSend() {
        config(false, "oc-group"); service.tick();
        assertThat(service.status("member", "tr-test").state()).isEqualTo("DISABLED");
        verifyNoInteractions(readings, feishu, webhookSender);
    }

    @Test void noSavedReplyDoesNotSendDemoOrPublishLink() {
        when(conversations.latestAssistantReply(anyString(), anyString())).thenReturn(null);
        service.tick();
        assertThat(store).isEmpty();
        verifyNoInteractions(readings, feishu);
    }

    @Test void quietHoursSkipAndDoNotCatchUpUntilExplicitRetry() {
        when(policies.allowsReminders()).thenReturn(false);
        when(policies.blockedReason()).thenReturn("沉默时段");
        service.tick();
        assertThat(service.status("member", "tr-test").state()).isEqualTo("SKIPPED");
        verify(readings, never()).createShareToken(anyString(), anyString());
        when(policies.allowsReminders()).thenReturn(true);
        service.tick(); verifyNoInteractions(feishu);
        service.retry("member", "tr-test", false); service.tick();
        assertThat(service.status("member", "tr-test").state()).isEqualTo("SENT");
    }

    @Test void transportUncertaintyRequiresAcknowledgmentAndKeepsSnapshot() {
        when(feishu.sendRawCard(anyString(), anyString(), any(), anyString())).thenThrow(new FeishuApiException("timeout"));
        service.tick();
        assertThat(service.status("member", "tr-test").state()).isEqualTo("UNKNOWN");
        String snapshot = store.get("tr-test").payload();
        assertThatThrownBy(() -> service.retry("member", "tr-test", false)).hasMessageContaining("确认");
        service.tick(); verify(feishu, times(1)).sendRawCard(anyString(), anyString(), any(), anyString());
        doReturn("om-receipt").when(feishu).sendRawCard(anyString(), anyString(), any(), anyString());
        service.retry("member", "tr-test", true); service.tick();
        assertThat(store.get("tr-test").payload()).isEqualTo(snapshot);
        assertThat(store.get("tr-test").state()).isEqualTo("SENT");
    }

    @Test void changedTargetCannotSilentlyRedirectRetry() {
        when(feishu.tenantAccessToken(anyString(), anyString())).thenThrow(new FeishuApiException("invalid credentials"));
        service.tick();
        assertThat(store.get("tr-test").state()).isEqualTo("FAILED");
        config(true, "oc-other");
        assertThatThrownBy(() -> service.retry("member", "tr-test", false)).hasMessageContaining("原目标");
        verify(feishu, never()).sendRawCard(anyString(), anyString(), any(), anyString());
    }

    @Test void otherMemberCannotReadOrRetry() {
        assertThatThrownBy(() -> service.status("other", "tr-test")).hasMessage("卦象不存在");
        assertThatThrownBy(() -> service.retry("other", "tr-test", true)).hasMessage("卦象不存在");
        verifyNoInteractions(deliveries, feishu);
    }

    @Test void lostClaimDoesNotSend() {
        when(deliveries.claim(eq("tr-test"), anyString(), any())).thenReturn(0);
        service.tick();
        verifyNoInteractions(feishu, webhookSender);
    }

    @Test void unsafeMarkupCannotMentionEveryoneOrInjectLinks() {
        String rendered = json.valueToTree(TianrenNotificationService.card(reading,
                "<at id=all>all</at> [危险](https://example.test) <font color=red>测试</font>" + "字".repeat(6000), ""))
                .path("body").path("elements").get(1).path("content").asText();
        assertThat(rendered).doesNotContain("<at", "example.test", "<font color=red>")
                .contains("内容较长", "文化反思");
        assertThat(rendered.length()).isLessThan(6000);
    }

    private void config(boolean enabled, String target) {
        when(preferences.find("member", WorkdayRhythmNotificationService.CONFIG_KEY)).thenReturn(
                "{\"energyNotification\":{\"enabled\":true,\"notifyTianren\":" + enabled
                        + ",\"channel\":\"CHAT\",\"chatId\":\"" + target + "\",\"chatName\":\"测试目标群\"}}");
    }
}
