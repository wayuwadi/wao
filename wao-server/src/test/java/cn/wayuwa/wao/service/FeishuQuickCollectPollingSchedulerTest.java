package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuMention;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuMessage;
import org.junit.jupiter.api.Test;

class FeishuQuickCollectPollingSchedulerTest {

    private final SettingService settings = mock(SettingService.class);
    private final FeishuOpenApiClient feishu = mock(FeishuOpenApiClient.class);
    private final FeishuQuickCollectService quickCollect = mock(FeishuQuickCollectService.class);
    private final FeishuQuickCollectPollingScheduler scheduler =
            new FeishuQuickCollectPollingScheduler(settings, feishu, quickCollect);

    @Test
    void disabledCollectorDoesNotCallFeishu() {
        scheduler.poll();

        verify(feishu, never()).tenantAccessToken(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void onlyUserMessageMentioningThisBotIsCollected() {
        enabled();
        when(feishu.tenantAccessToken("app", "secret")).thenReturn("token");
        when(feishu.botOpenId("token")).thenReturn("ou_bot");
        FeishuMessage eligible = message("om_yes", "user", false, "ou_bot");
        when(feishu.listMessages(org.mockito.ArgumentMatchers.eq("token"),
                org.mockito.ArgumentMatchers.eq("oc_chat"), anyLong(), anyLong()))
                .thenReturn(List.of(
                        eligible,
                        message("om_other", "user", false, "ou_someone_else"),
                        message("om_app", "app", false, "ou_bot"),
                        message("om_deleted", "user", true, "ou_bot")));

        scheduler.poll();

        verify(quickCollect).collectPolled("oc_chat", eligible);
        verify(quickCollect).triggerPolledReaction("token", "om_yes");
        verify(quickCollect).recallPolled("oc_chat", "om_deleted");
        verify(quickCollect, org.mockito.Mockito.times(1)).collectPolled(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void eligibilityRequiresExactBotMention() {
        assertThat(FeishuQuickCollectPollingScheduler.eligible(
                message("om_1", "user", false, "ou_bot"), "ou_bot")).isTrue();
        assertThat(FeishuQuickCollectPollingScheduler.eligible(
                message("om_2", "user", false, "ou_person"), "ou_bot")).isFalse();
    }

    @Test
    void editSweepReplaysCurrentMessageContentThroughTheIdempotentCollector() {
        enabled();
        when(feishu.tenantAccessToken("app", "secret")).thenReturn("token");
        when(feishu.botOpenId("token")).thenReturn("ou_bot");
        FeishuMessage edited = message("om_edited", "user", false, "ou_bot");
        when(feishu.listMessages(org.mockito.ArgumentMatchers.eq("token"),
                org.mockito.ArgumentMatchers.eq("oc_chat"), anyLong(), anyLong()))
                .thenReturn(List.of(edited));

        scheduler.pollEdits();

        verify(quickCollect).collectPolled("oc_chat", edited);
        verify(quickCollect).triggerPolledReaction("token", "om_edited");
    }

    @Test
    void editSweepAlsoConvergesARecalledMessage() {
        enabled();
        when(feishu.tenantAccessToken("app", "secret")).thenReturn("token");
        when(feishu.botOpenId("token")).thenReturn("ou_bot");
        when(feishu.listMessages(org.mockito.ArgumentMatchers.eq("token"),
                org.mockito.ArgumentMatchers.eq("oc_chat"), anyLong(), anyLong()))
                .thenReturn(List.of(message("om_recalled", "user", true, "ou_bot")));

        scheduler.pollEdits();

        verify(quickCollect).recallPolled("oc_chat", "om_recalled");
        verify(quickCollect, never()).collectPolled(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private void enabled() {
        when(settings.getBoolean(SettingService.QUICK_COLLECT_ENABLED)).thenReturn(true);
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("app");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("secret");
        when(settings.get(SettingService.CHAT_SYNC_CHAT_ID, "")).thenReturn("oc_chat");
    }

    private static FeishuMessage message(String id, String senderType, boolean deleted, String mentionedOpenId) {
        return new FeishuMessage(id, "ou_sender", senderType, "text",
                "{\"text\":\"@_user_1 记录任务：整理周报\"}", 1L, deleted,
                List.of(new FeishuMention("@_user_1", mentionedOpenId, "WAO")));
    }
}
