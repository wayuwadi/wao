package cn.wayuwa.wao.feishu;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.wayuwa.wao.service.FeishuWebhookConfigService;
import cn.wayuwa.wao.service.NotificationSettingsService;
import cn.wayuwa.wao.service.SettingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FeishuWebhookNotifierTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"'{\"code\":0}',true", "'{\"StatusCode\":0}',true", "'{}',false", "'{\"code\":19001}',false"})
    void resetCardIncreaseRequiresConfirmedFeishuDelivery(String response, boolean accepted) {
        var webhooks = mock(FeishuWebhookConfigService.class);
        var policy = mock(NotificationSettingsService.class);
        when(policy.allows(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(webhooks.defaultTarget()).thenReturn(Optional.of(
                new FeishuWebhookConfigService.NotificationTarget("fw1", "默认群", "https://open.feishu.test/hook", true)));
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://open.feishu.test/hook")).andExpect(method(POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2 张 → 3 张")))
                .andRespond(withSuccess(response, org.springframework.http.MediaType.APPLICATION_JSON));
        var sender = new FeishuWebhookNotifier(mock(SettingService.class), webhooks, policy, builder, "", null);
        assertThat(sender.notifyResetCardIncrease(FeishuWebhookNotifier.Card.simple(
                "Codex 重置卡已增加", "green", "2 张 → 3 张"))).isEqualTo(accepted);
        server.verify();
    }

    @Test
    void resetCardIncreaseRespectsQuietAndDisabledTarget() {
        var webhooks = mock(FeishuWebhookConfigService.class);
        var policy = mock(NotificationSettingsService.class);
        var sender = new FeishuWebhookNotifier(mock(SettingService.class), webhooks, policy, RestClient.builder(), "", null);
        var card = FeishuWebhookNotifier.Card.simple("重置卡", "green", "2 张 → 3 张");
        assertThat(sender.notifyResetCardIncrease(card)).isFalse();
        assertThat(sender.notifyCodexQuotaRefresh(card)).isFalse();
        verifyNoInteractions(webhooks);
        when(policy.allows(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(webhooks.defaultTarget()).thenReturn(Optional.of(
                new FeishuWebhookConfigService.NotificationTarget("fw1", "默认群", "https://open.feishu.test/hook", false)));
        assertThat(sender.notifyResetCardIncrease(card)).isFalse();
        assertThat(sender.notifyCodexQuotaRefresh(card)).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"'{\"code\":0}',true", "'{\"StatusCode\":0}',true", "'{}',false", "'{\"code\":19001}',false"})
    void codexQuotaRefreshUsesDefaultGroupAndRequiresFeishuConfirmation(String response, boolean accepted) {
        var webhooks = mock(FeishuWebhookConfigService.class);
        var policy = mock(NotificationSettingsService.class);
        when(policy.allows(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(webhooks.defaultTarget()).thenReturn(Optional.of(
                new FeishuWebhookConfigService.NotificationTarget("fw1", "默认群", "https://open.feishu.test/hook", true)));
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://open.feishu.test/hook")).andExpect(method(POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("80% → 100%")))
                .andRespond(withSuccess(response, org.springframework.http.MediaType.APPLICATION_JSON));
        var sender = new FeishuWebhookNotifier(mock(SettingService.class), webhooks, policy, builder, "", null);
        assertThat(sender.notifyCodexQuotaRefresh(FeishuWebhookNotifier.Card.simple(
                "AI 额度已刷新", "green", "周额度：80% → 100%"))).isEqualTo(accepted);
        server.verify();
    }

    @Test
    void rawCardRequiresExplicitSuccessNotEmptyBody() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://open.feishu.test/hook"))
                .andExpect(content().json("{\"msg_type\":\"interactive\",\"card\":{\"schema\":\"2.0\"}}"))
                .andRespond(withSuccess("{}", org.springframework.http.MediaType.APPLICATION_JSON));
        var sender = new FeishuWebhookNotifier(mock(SettingService.class), mock(FeishuWebhookConfigService.class),
                mock(NotificationSettingsService.class), builder, "", null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sender.sendRawCard("https://open.feishu.test/hook",
                mapper.readTree("{\"schema\":\"2.0\"}"))).isInstanceOf(FeishuApiException.class);
        server.verify();
    }

    @Test
    void comicCardPutsImageAsMarkdownAndKeepsButton() throws Exception {
        FeishuWebhookNotifier.ComicCard card = new FeishuWebhookNotifier.ComicCard(
                "AI 洞察报告", "blue",
                "https://wao.wayuwa.cn/comic/insight.svg", "洞察插图",
                "发现了 7 条想法",
                List.of("想法 1", "想法 2"),
                List.of(List.of("项目", "Wa All in One")),
                "正文",
                "查看详情", "https://wao.wayuwa.cn/projects/p1");

        Map<String, Object> payload = card.toPayload();
        JsonNode root = mapper.valueToTree(payload);

        assertThat(root.path("header").path("title").path("content").asText()).isEqualTo("AI 洞察报告");
        assertThat(root.path("elements").get(0).path("text").path("content").asText())
                .isEqualTo("![洞察插图](https://wao.wayuwa.cn/comic/insight.svg)");
        assertThat(root.path("elements").get(1).path("text").path("content").asText())
                .contains("发现了 7 条想法");

        JsonNode action = root.path("elements").get(root.path("elements").size() - 1);
        assertThat(action.path("tag").asText()).isEqualTo("action");
        assertThat(action.path("actions").get(0).path("url").asText())
                .isEqualTo("https://wao.wayuwa.cn/projects/p1");
    }

    @Test
    void comicCardSkipsEmptyHighlightsAndFields() {
        FeishuWebhookNotifier.ComicCard card = FeishuWebhookNotifier.ComicCard.visual(
                "完成", "green", "https://wao.wayuwa.cn/comic/success.svg",
                "任务已完成", "查看任务", "https://wao.wayuwa.cn/tasks/t1");

        Map<String, Object> payload = card.toPayload();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> elements = (List<Map<String, Object>>) payload.get("elements");
        assertThat(elements).hasSize(3); // image, headline, button
    }

    @Test
    void comicIllustrationsBuildsAbsoluteUrls() {
        assertThat(FeishuWebhookNotifier.ComicIllustrations.insight("https://wao.wayuwa.cn"))
                .isEqualTo("https://wao.wayuwa.cn/comic/insight.svg");
        assertThat(FeishuWebhookNotifier.ComicIllustrations.success("https://wao.wayuwa.cn/"))
                .isEqualTo("https://wao.wayuwa.cn/comic/success.svg");
    }

    @Test
    void existingNotificationsUseTheEnabledRegistryDefaultWithoutReadingLegacyUrl() {
        SettingService settings = mock(SettingService.class);
        FeishuWebhookConfigService webhooks = mock(FeishuWebhookConfigService.class);
        String url = "https://open.feishu.cn/open-apis/bot/v2/hook/server-only-secret";
        when(webhooks.defaultTarget()).thenReturn(Optional.of(
                new FeishuWebhookConfigService.NotificationTarget("fw1", "默认机器人", url, true)));
        FeishuWebhookNotifier notifier = new FeishuWebhookNotifier(
                settings, webhooks, mock(NotificationSettingsService.class),
                RestClient.builder(), "https://legacy.invalid/hook");

        assertThat(notifier.resolvedUrl()).isEqualTo(url);
        assertThat(notifier.enabled()).isTrue();
    }

    @Test
    void deletingAllManagedConfigsDoesNotRestoreTheLegacyDeploymentUrl() {
        SettingService settings = mock(SettingService.class);
        FeishuWebhookConfigService webhooks = mock(FeishuWebhookConfigService.class);
        when(webhooks.defaultTarget()).thenReturn(Optional.empty());
        when(webhooks.registryInitialized()).thenReturn(true);
        FeishuWebhookNotifier notifier = new FeishuWebhookNotifier(
                settings, webhooks, mock(NotificationSettingsService.class),
                RestClient.builder(), "https://legacy.invalid/hook");

        assertThat(notifier.resolvedUrl()).isEmpty();
        assertThat(notifier.enabled()).isFalse();
    }

    @Test
    void globalQuietGateStopsBusinessWebhookBeforeResolvingAnySecretTarget() {
        SettingService settings = mock(SettingService.class);
        FeishuWebhookConfigService webhooks = mock(FeishuWebhookConfigService.class);
        NotificationSettingsService notificationSettings = mock(NotificationSettingsService.class);
        when(notificationSettings.allows(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        FeishuWebhookNotifier notifier = new FeishuWebhookNotifier(
                settings, webhooks, notificationSettings, RestClient.builder(), "https://legacy.invalid/hook");

        assertThat(notifier.notifyCard(FeishuWebhookNotifier.Card.simple("提醒", "orange", "正文"))).isFalse();
        verifyNoInteractions(webhooks);
    }

    @Test
    void explicitTextReplyUsesSelectedWebhookAndTextPayload() {
        SettingService settings = mock(SettingService.class);
        FeishuWebhookConfigService webhooks = mock(FeishuWebhookConfigService.class);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String url = "https://open.feishu.cn/open-apis/bot/v2/hook/server-only-secret";
        when(webhooks.enabledTarget("fw-polish")).thenReturn(
                new FeishuWebhookConfigService.NotificationTarget(
                        "fw-polish", "润色机器人", url, true));
        server.expect(requestTo(url)).andExpect(method(POST))
                .andExpect(content().json("""
                        {"msg_type":"text","content":{"text":"润色结果：\\n整理后的正文"}}
                        """))
                .andRespond(withSuccess("{\"code\":0,\"msg\":\"success\"}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        FeishuWebhookNotifier notifier = new FeishuWebhookNotifier(
                settings, webhooks, mock(NotificationSettingsService.class), builder, "");

        notifier.sendText("fw-polish", "润色结果：\n整理后的正文");

        server.verify();
    }
}
