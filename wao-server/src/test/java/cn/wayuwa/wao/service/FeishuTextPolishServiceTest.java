package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.FeishuTextPolishRunEntity;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuMention;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuMessage;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.FeishuTextPolishRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FeishuTextPolishServiceTest {
    private final FeishuTextPolishConfigService config = mock(FeishuTextPolishConfigService.class);
    private final FeishuMemberIdentityService identities = mock(FeishuMemberIdentityService.class);
    private final FeishuTextPolishRunMapper runs = mock(FeishuTextPolishRunMapper.class);
    private final TextPolishService textPolish = mock(TextPolishService.class);
    private final FeishuTextPolishAiQueue queue = mock(FeishuTextPolishAiQueue.class);
    private final FeishuTextPolishCompletionService completion = mock(FeishuTextPolishCompletionService.class);
    private final AiAssistantService assistant = mock(AiAssistantService.class);
    private final FeishuWebhookNotifier notifier = mock(FeishuWebhookNotifier.class);
    private final SettingService settings = mock(SettingService.class);
    private final FeishuOpenApiClient feishu = mock(FeishuOpenApiClient.class);
    private final FeishuTextPolishService service = new FeishuTextPolishService(
            config, identities, runs, textPolish, queue, completion, assistant, notifier,
            settings, feishu, new ObjectMapper());

    @BeforeEach
    void configured() {
        when(config.enabled()).thenReturn(true);
        when(config.mentionName()).thenReturn("润色机器人");
        when(config.webhookId()).thenReturn("fw-polish");
        when(settings.get(SettingService.CHAT_SYNC_CHAT_ID, "")).thenReturn("oc-chat");
        when(identities.resolvePolledMember("oc-chat", "ou-sender")).thenReturn("wd");
        when(textPolish.prepare("wd", "需要整理，另外请 @小王 一起确认。"))
                .thenReturn(new TextPolishService.PreparedPolish(
                        "需要整理，另外请 @小王 一起确认。", "先结论后步骤"));
    }

    @Test
    void exactMentionCreatesOneQueuedRunWithMemberPromptSnapshot() {
        FeishuMessage source = textMessage("om-1", "user", false,
                "{\"text\":\"@_user_1 需要整理，另外请 @_user_2 一起确认。\"}",
                List.of(
                        new FeishuMention("@_user_1", "", "润色机器人"),
                        new FeishuMention("@_user_2", "ou-xiao-wang", "小王")));

        service.collectPolled("oc-chat", source);

        ArgumentCaptor<FeishuTextPolishRunEntity> saved =
                ArgumentCaptor.forClass(FeishuTextPolishRunEntity.class);
        verify(runs).insert(saved.capture());
        assertThat(saved.getValue().getMessageId()).isEqualTo("om-1");
        assertThat(saved.getValue().getMemberId()).isEqualTo("wd");
        assertThat(saved.getValue().getOriginalText()).isEqualTo("需要整理，另外请 @小王 一起确认。");
        assertThat(saved.getValue().getPromptSnapshot()).isEqualTo("先结论后步骤");
        assertThat(saved.getValue().getStatus()).isEqualTo("QUEUED");
        verify(notifier, never()).sendText(anyString(), anyString());
    }

    @Test
    void ignoresOtherMentionsBotMessagesDeletedMessagesAndUnsupportedTypes() {
        service.collectPolled("oc-chat", textMessage("om-other", "user", false,
                "{\"text\":\"@_user_1 普通消息\"}",
                List.of(new FeishuMention("@_user_1", "ou-other", "其他机器人"))));
        service.collectPolled("oc-chat", textMessage("om-app", "app", false,
                "{\"text\":\"@_user_1 机器人消息\"}", trigger()));
        service.collectPolled("oc-chat", textMessage("om-deleted", "user", true,
                "{\"text\":\"@_user_1 已撤回\"}", trigger()));
        service.collectPolled("oc-chat", new FeishuMessage("om-image", "ou-sender", "user",
                "image", "{}", 1L, false, trigger(), "", ""));

        verify(runs, never()).insert(any(FeishuTextPolishRunEntity.class));
    }

    @Test
    void richTextRemovesTriggerButPreservesOtherPeople() {
        FeishuMessage source = new FeishuMessage("om-post", "ou-sender", "user", "post",
                "{\"title\":\"标题\",\"content\":[[{\"tag\":\"at\",\"user_name\":\"润色机器人\"},"
                        + "{\"tag\":\"text\",\"text\":\"请和\"},{\"tag\":\"at\",\"user_name\":\"小王\"},"
                        + "{\"tag\":\"text\",\"text\":\"确认\"}]]}",
                1L, false, trigger(), "", "");

        assertThat(service.readableText(source, "润色机器人"))
                .isEqualTo("标题\n请和@小王确认");
    }

    @Test
    void unboundSenderGetsFailureWithoutCreatingAiHistory() {
        when(identities.resolvePolledMember("oc-chat", "ou-sender")).thenReturn(null);

        service.collectPolled("oc-chat", textMessage("om-unbound", "user", false,
                "{\"text\":\"@_user_1 请润色\"}", trigger()));

        ArgumentCaptor<FeishuTextPolishRunEntity> saved =
                ArgumentCaptor.forClass(FeishuTextPolishRunEntity.class);
        verify(runs).insert(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(saved.getValue().getErrorMessage()).contains("绑定");
        verify(notifier).sendText("fw-polish", "润色失败：请先使用同一飞书账号登录 WAO 完成成员绑定");
        verify(queue, never()).enqueue(anyString());
    }

    @Test
    void advancesAiDeliveryAndHistoryInOrder() {
        FeishuTextPolishRunEntity queued = run("fp1", "QUEUED");
        FeishuTextPolishRunEntity pending = run("fp2", "AI_PENDING");
        pending.setAiRunId("ac2");
        FeishuTextPolishRunEntity generated = run("fp3", "GENERATED");
        generated.setPolishedText("清楚的结果");
        FeishuTextPolishRunEntity delivered = run("fp4", "DELIVERED");
        when(runs.findAdvanceable()).thenReturn(List.of(queued, pending, generated, delivered));
        when(assistant.readOnlyJobResult("fp2", "ac2"))
                .thenReturn(new AiAssistantService.ReadOnlyJobResult(
                        "ac2", "succeeded", "AI 润色结果", null));
        when(runs.claimGenerated(eq("fp3"), any())).thenReturn(1);
        when(completion.complete("fp4")).thenReturn(true);

        service.advance();

        verify(queue).enqueue("fp1");
        verify(runs).markGenerated(eq("fp2"), eq("AI 润色结果"), any());
        verify(notifier).sendText("fw-polish", "润色结果：\n清楚的结果");
        verify(runs).markDelivered(eq("fp3"), any());
        verify(completion).complete("fp4");
    }

    @Test
    void disabledWorkerDoesNotTouchRunTable() {
        when(config.enabled()).thenReturn(false);

        service.advance();

        verify(runs, never()).findAdvanceable();
        verify(runs, never()).recoverStaleRecording(any(), any());
    }

    @Test
    void longUtf8ResultIsSplitWithoutLosingCharacters() {
        String original = "中".repeat(12_000);
        List<String> messages = FeishuTextPolishService.resultMessages(original);

        assertThat(messages).hasSizeGreaterThan(1);
        assertThat(messages).allMatch(message ->
                message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 24_000);
        assertThat(messages.stream().map(message -> message.substring(message.indexOf('\n') + 1))
                .reduce("", String::concat)).isEqualTo(original);
    }

    private static FeishuTextPolishRunEntity run(String id, String status) {
        FeishuTextPolishRunEntity run = new FeishuTextPolishRunEntity();
        run.setId(id);
        run.setStatus(status);
        run.setWebhookId("fw-polish");
        run.setMessageId("om-" + id);
        return run;
    }

    private static List<FeishuMention> trigger() {
        return List.of(new FeishuMention("@_user_1", "", "润色机器人"));
    }

    private static FeishuMessage textMessage(String id, String senderType, boolean deleted,
            String content, List<FeishuMention> mentions) {
        return new FeishuMessage(id, "ou-sender", senderType, "text", content,
                1L, deleted, mentions, "", "");
    }
}
