package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.domain.AutomationJobEntity;
import cn.wayuwa.wao.domain.FeishuNotificationEntity;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.mapper.FeishuNotificationMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FeishuNotificationHandlerTest {
    @Test void storesOriginalMessageAndContentForFutureFeedbackEvenIfJobChanges() {
        var settings = mock(SettingService.class);
        var feishu = mock(FeishuOpenApiClient.class);
        var notifications = mock(FeishuNotificationMapper.class);
        var policy = mock(NotificationSettingsService.class);
        when(policy.allowsReminders()).thenReturn(true);
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("app");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("secret");
        when(settings.get(SettingService.CHAT_SYNC_CHAT_ID, "")).thenReturn("oc_group");
        when(feishu.tenantAccessToken("app", "secret")).thenReturn("token");
        when(feishu.sendFeedbackCard(eq("token"), eq("oc_group"), eq("下班提醒"), eq("休息并打卡"), anyString())).thenReturn("om_sent");
        var job = new AutomationJobEntity(); job.setId("job1"); job.setName("下班提醒"); job.setDescription("休息并打卡");
        var handler = new FeishuNotificationHandler(settings, feishu, notifications, policy);
        var internal = mock(InternalNotificationService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(handler, "internalNotifications", internal);
        handler.execute(job);
        verify(internal).publish(any());
        verifyNoInteractions(feishu);
        var captured = ArgumentCaptor.forClass(FeishuNotificationEntity.class);
        verify(notifications).insert(captured.capture());
        assertThat(captured.getValue()).extracting(FeishuNotificationEntity::getCardTitle, FeishuNotificationEntity::getCardBody)
                .containsExactly("下班提醒", "休息并打卡");
    }
}
