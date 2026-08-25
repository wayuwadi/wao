package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import cn.wayuwa.wao.auth.FeishuLoginProperties;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.dto.SendEnergyNotificationRequest;
import cn.wayuwa.wao.feishu.FeishuAppLinks;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkdayRhythmNotificationServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 18);

    private final MemberPreferenceMapper preferences = mock(MemberPreferenceMapper.class);
    private final MemberMapper members = mock(MemberMapper.class);
    private final SettingService settings = mock(SettingService.class);
    private final FeishuOpenApiClient feishu = mock(FeishuOpenApiClient.class);
    private final FeishuWebhookConfigService webhooks = mock(FeishuWebhookConfigService.class);
    private final FeishuWebhookNotifier webhookNotifier = mock(FeishuWebhookNotifier.class);
    private final FeishuAppLinks links = new FeishuAppLinks(
            new FeishuLoginProperties(false, "", "", "", "", "https://open.feishu.cn"));
    private final WorkdayRhythmNotificationService service = new WorkdayRhythmNotificationService(
            preferences, members, settings, feishu, links, webhooks, webhookNotifier, new ObjectMapper());

    @Test
    void sendsPersistedWaterRecordToMembersOwnConfiguredChat() {
        configured(true, true);
        when(preferences.find("wd", "workday-rhythm-day-2026-08-18"))
                .thenReturn("{\"waterCount\":3,\"meditated\":false}");
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("app-id");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("app-secret");
        when(feishu.tenantAccessToken("app-id", "app-secret")).thenReturn("tenant-token");
        MemberEntity member = new MemberEntity();
        member.setId("wd");
        member.setName("娃弟");
        when(members.selectById("wd")).thenReturn(member);

        var result = service.notifyEnergy("wd", new SendEnergyNotificationRequest("WATER", DAY));

        assertThat(result.sent()).isTrue();
        assertThat(result.chatName()).isEqualTo("娃屋日常");
        ArgumentCaptor<FeishuWebhookNotifier.Card> card = ArgumentCaptor.forClass(FeishuWebhookNotifier.Card.class);
        verify(feishu).sendCard(org.mockito.ArgumentMatchers.eq("tenant-token"),
                org.mockito.ArgumentMatchers.eq("oc_target"), card.capture());
        assertThat(card.getValue().title()).isEqualTo("精力补给 · 喝水 +1");
        assertThat(card.getValue().fields()).contains(java.util.List.of("成员", "娃弟"),
                java.util.List.of("今日记录", "已记录 3 次"));
    }

    @Test
    void disabledActionDoesNotReadAnotherMembersDayOrSend() {
        configured(true, false);

        var result = service.notifyEnergy("wd", new SendEnergyNotificationRequest("MEDITATION", DAY));

        assertThat(result.sent()).isFalse();
        verify(preferences, never()).find("wd", "workday-rhythm-day-2026-08-18");
        verify(feishu, never()).sendCard(any(), any(), any());
    }

    @Test
    void sendsMeditationOnlyAfterTheMembersCompletionWasPersisted() {
        configured(true, true);
        when(preferences.find("wd", "workday-rhythm-day-2026-08-18"))
                .thenReturn("{\"waterCount\":1,\"meditated\":true}");
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("app-id");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("app-secret");
        when(feishu.tenantAccessToken("app-id", "app-secret")).thenReturn("tenant-token");

        var result = service.notifyEnergy("wd", new SendEnergyNotificationRequest("MEDITATION", DAY));

        assertThat(result.sent()).isTrue();
        ArgumentCaptor<FeishuWebhookNotifier.Card> card = ArgumentCaptor.forClass(FeishuWebhookNotifier.Card.class);
        verify(feishu).sendCard(org.mockito.ArgumentMatchers.eq("tenant-token"),
                org.mockito.ArgumentMatchers.eq("oc_target"), card.capture());
        assertThat(card.getValue().title()).isEqualTo("精力补给 · 至少冥想一次");
        assertThat(card.getValue().fields()).contains(java.util.List.of("今日记录", "今日已完成"));
    }

    @Test
    void rejectsNotificationWhenTheClaimedActionWasNotPersisted() {
        configured(true, true);
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("app-id");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("app-secret");
        when(preferences.find("wd", "workday-rhythm-day-2026-08-18"))
                .thenReturn("{\"waterCount\":0,\"meditated\":false}");

        assertThatThrownBy(() -> service.notifyEnergy("wd", new SendEnergyNotificationRequest("WATER", DAY)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("尚未保存");
        verify(feishu, never()).sendCard(any(), any(), any());
    }

    @Test
    void sendsConfiguredCustomEnergyItemFromPersistedRecord() {
        when(preferences.find("wd", WorkdayRhythmNotificationService.CONFIG_KEY)).thenReturn("""
                {"energyNotification":{"enabled":true,"chatId":"oc_target","chatName":"娃屋日常"},
                "energyItems":[{"id":"WALK","title":"起身走动","guidance":"活动几分钟",
                "mode":"DAILY_ONCE","notify":true}]}
                """);
        when(preferences.find("wd", "workday-rhythm-day-2026-08-18"))
                .thenReturn("{\"energyRecords\":{\"WALK\":1}}");
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("app-id");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("app-secret");
        when(feishu.tenantAccessToken("app-id", "app-secret")).thenReturn("tenant-token");

        var result = service.notifyEnergy("wd", new SendEnergyNotificationRequest("WALK", DAY));

        assertThat(result.sent()).isTrue();
        ArgumentCaptor<FeishuWebhookNotifier.Card> card = ArgumentCaptor.forClass(FeishuWebhookNotifier.Card.class);
        verify(feishu).sendCard(org.mockito.ArgumentMatchers.eq("tenant-token"),
                org.mockito.ArgumentMatchers.eq("oc_target"), card.capture());
        assertThat(card.getValue().title()).isEqualTo("精力补给 · 起身走动");
        assertThat(card.getValue().fields()).contains(java.util.List.of("今日记录", "今日已完成"));
    }

    @Test
    void sendsPersistedActionThroughSelectedSystemWebhookWithoutAppCredentials() {
        when(preferences.find("wd", WorkdayRhythmNotificationService.CONFIG_KEY)).thenReturn("""
                {"energyNotification":{"enabled":true,"channel":"WEBHOOK",
                "webhookId":"fw_energy","webhookName":"过期名称"},
                "energyItems":[{"id":"WATER","title":"喝水","guidance":"补水",
                "mode":"COUNT","notify":true}]}
                """);
        when(preferences.find("wd", "workday-rhythm-day-2026-08-18"))
                .thenReturn("{\"energyRecords\":{\"WATER\":2}}");
        when(webhooks.enabledTarget("fw_energy")).thenReturn(
                new FeishuWebhookConfigService.NotificationTarget(
                        "fw_energy", "精力补给群", "https://open.feishu.cn/safe-placeholder", true));

        var result = service.notifyEnergy("wd", new SendEnergyNotificationRequest("WATER", DAY));

        assertThat(result.sent()).isTrue();
        assertThat(result.chatName()).isEqualTo("精力补给群");
        verify(webhookNotifier).send(org.mockito.ArgumentMatchers.eq("https://open.feishu.cn/safe-placeholder"),
                any(FeishuWebhookNotifier.Card.class));
        verify(feishu, never()).tenantAccessToken(any(), any());
    }

    @Test
    void sendsThreePersistedFocusItemsToMembersConfiguredTarget() {
        when(preferences.find("wd", WorkdayRhythmNotificationService.CONFIG_KEY)).thenReturn("""
                {"energyNotification":{"enabled":true,"notifyFocus":true,
                "chatId":"oc_target","chatName":"娃屋日常"}}
                """);
        when(preferences.find("wd", "workday-rhythm-day-2026-08-18")).thenReturn("""
                {"focus":["交付最小 MVP","完成上线验收","整理真实反馈"]}
                """);
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("app-id");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("app-secret");
        when(feishu.tenantAccessToken("app-id", "app-secret")).thenReturn("tenant-token");
        MemberEntity member = new MemberEntity();
        member.setId("wd"); member.setName("娃弟"); when(members.selectById("wd")).thenReturn(member);

        var result = service.notifyFocus("wd", DAY);

        assertThat(result.sent()).isTrue();
        ArgumentCaptor<FeishuWebhookNotifier.Card> card = ArgumentCaptor.forClass(FeishuWebhookNotifier.Card.class);
        verify(feishu).sendCard(org.mockito.ArgumentMatchers.eq("tenant-token"),
                org.mockito.ArgumentMatchers.eq("oc_target"), card.capture());
        assertThat(card.getValue().title()).isEqualTo("今日三个重点 · 已承诺");
        assertThat(card.getValue().fields()).contains(java.util.List.of("成员", "娃弟"),
                java.util.List.of("重点 1", "交付最小 MVP"),
                java.util.List.of("重点 3", "整理真实反馈"));
    }

    @Test
    void rejectsFocusNotificationUntilAllThreeItemsWerePersisted() {
        when(preferences.find("wd", WorkdayRhythmNotificationService.CONFIG_KEY)).thenReturn("""
                {"energyNotification":{"enabled":true,"notifyFocus":true,"chatId":"oc_target"}}
                """);
        when(preferences.find("wd", "workday-rhythm-day-2026-08-18"))
                .thenReturn("{\"focus\":[\"只有一项\",\"\",\"\"]}");

        assertThatThrownBy(() -> service.notifyFocus("wd", DAY))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("尚未完整保存");
        verify(feishu, never()).sendCard(any(), any(), any());
    }

    @Test
    void disabledFocusNotificationDoesNotReadTheDay() {
        when(preferences.find("wd", WorkdayRhythmNotificationService.CONFIG_KEY)).thenReturn("""
                {"energyNotification":{"enabled":true,"notifyFocus":false,"chatId":"oc_target"}}
                """);

        assertThat(service.notifyFocus("wd", DAY).sent()).isFalse();
        verify(preferences, never()).find("wd", "workday-rhythm-day-2026-08-18");
    }

    @Test
    void rejectsDeletedOrUnknownEnergyItemBeforeReadingDay() {
        when(preferences.find("wd", WorkdayRhythmNotificationService.CONFIG_KEY)).thenReturn("""
                {"energyNotification":{"enabled":true},"energyItems":[]}
                """);

        assertThatThrownBy(() -> service.notifyEnergy("wd", new SendEnergyNotificationRequest("REMOVED", DAY)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("不存在或已删除");
        verify(preferences, never()).find("wd", "workday-rhythm-day-2026-08-18");
        verify(feishu, never()).sendCard(any(), any(), any());
    }

    private void configured(boolean enabled, boolean meditation) {
        when(preferences.find("wd", WorkdayRhythmNotificationService.CONFIG_KEY)).thenReturn("""
                {"energyNotification":{"enabled":%s,"chatId":"oc_target","chatName":"娃屋日常",
                "notifyWater":true,"notifyMeditation":%s}}
                """.formatted(enabled, meditation));
    }
}
