package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.dto.FeishuWebhookEndpointDto;
import cn.wayuwa.wao.dto.SaveFeishuTextPolishConfigRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeishuTextPolishConfigServiceTest {
    private final SettingService settings = mock(SettingService.class);
    private final FeishuWebhookConfigService webhooks = mock(FeishuWebhookConfigService.class);
    private final FeishuTextPolishConfigService service =
            new FeishuTextPolishConfigService(settings, webhooks);

    @Test
    void enabledConfigAcceptsOptionalUsableWebhookAndStoresOnlyStableId() {
        when(webhooks.enabledTarget("fw-polish")).thenReturn(
                new FeishuWebhookConfigService.NotificationTarget(
                        "fw-polish", "润色机器人", "https://secret.invalid/hook", true));
        when(settings.get(SettingService.FEISHU_TEXT_POLISH_WEBHOOK_ID, ""))
                .thenReturn("fw-polish");
        when(settings.get(SettingService.FEISHU_TEXT_POLISH_MENTION_NAME,
                FeishuTextPolishConfigService.DEFAULT_MENTION_NAME)).thenReturn("润色机器人");
        when(settings.getBoolean(SettingService.FEISHU_TEXT_POLISH_ENABLED)).thenReturn(true);
        when(webhooks.list()).thenReturn(List.of(new FeishuWebhookEndpointDto(
                "fw-polish", "润色机器人", "", "open.feishu.cn/…abcd", true,
                true, false, LocalDateTime.now(), LocalDateTime.now())));

        var saved = service.save(new SaveFeishuTextPolishConfigRequest(
                true, "fw-polish", " 润色机器人 "));

        assertThat(saved.webhookId()).isEqualTo("fw-polish");
        assertThat(saved.webhookName()).isEqualTo("润色机器人");
        verify(webhooks).enabledTarget("fw-polish");
        verify(settings).put(SettingService.FEISHU_TEXT_POLISH_WEBHOOK_ID, "fw-polish");
        verify(settings).put(SettingService.FEISHU_TEXT_POLISH_MENTION_NAME, "润色机器人");
    }

    @Test
    void canUseAppReplyWithoutWebhookButStillRequiresMentionName() {
        when(settings.get(SettingService.FEISHU_TEXT_POLISH_WEBHOOK_ID, "")).thenReturn("");
        when(settings.get(SettingService.FEISHU_TEXT_POLISH_MENTION_NAME,
                FeishuTextPolishConfigService.DEFAULT_MENTION_NAME)).thenReturn("润色机器人");
        when(settings.getBoolean(SettingService.FEISHU_TEXT_POLISH_ENABLED)).thenReturn(true);

        var saved = service.save(new SaveFeishuTextPolishConfigRequest(true, "", "润色机器人"));

        assertThat(saved.enabled()).isTrue();
        verify(settings).put(SettingService.FEISHU_TEXT_POLISH_WEBHOOK_ID, "");
        assertThatThrownBy(() -> service.save(
                new SaveFeishuTextPolishConfigRequest(false, "", "  ")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("触发名称");
    }
}
