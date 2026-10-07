package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.AccountEntity;
import cn.wayuwa.wao.domain.AccountIdentityEntity;
import cn.wayuwa.wao.domain.FeishuQuickCollectEventEntity;
import cn.wayuwa.wao.dto.ImageItemDto;
import cn.wayuwa.wao.dto.CreateNoteRequest;
import cn.wayuwa.wao.dto.ProjectDto;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.feishu.FeishuApiException;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuMention;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuMessage;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuMessageReaction;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import cn.wayuwa.wao.mapper.FeishuQuickCollectEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.dao.DuplicateKeyException;

class FeishuQuickCollectServiceTest {

    private final SettingService settings = mock(SettingService.class);
    private final FeishuOpenApiClient feishu = mock(FeishuOpenApiClient.class);
    private final TaskService tasks = mock(TaskService.class);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final AccountMapper accounts = mock(AccountMapper.class);
    private final AccountIdentityMapper identities = mock(AccountIdentityMapper.class);
    private final FeishuQuickCollectEventMapper events = mock(FeishuQuickCollectEventMapper.class);
    private final ImageService images = mock(ImageService.class);
    private final NotificationFeedbackService feedback = mock(NotificationFeedbackService.class);
    private final FeishuQuickCollectAiRunService reactionAiRuns = mock(FeishuQuickCollectAiRunService.class);
    private final FeishuQuickCollectReactionPolicy reactionPolicy = new FeishuQuickCollectReactionPolicy(settings);
    private final FeishuQuickCollectRecallService recalls = mock(FeishuQuickCollectRecallService.class);
    private final FeishuLinkPreviewService linkPreviews = mock(FeishuLinkPreviewService.class);
    private final FeishuQuickCollectService service = new FeishuQuickCollectService(
            settings, feishu, tasks, knowledge, projects, accounts, identities, events, images, new ObjectMapper(), feedback,
            reactionAiRuns, reactionPolicy, recalls, linkPreviews);

    @BeforeEach
    void authenticatedReceiver() {
        when(settings.get(SettingService.QUICK_COLLECT_VERIFY_TOKEN)).thenReturn("real-token");
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("cli_chat");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("secret");
        when(settings.get(SettingService.CHAT_SYNC_CHAT_ID, "")).thenReturn("oc_1");
    }

    private void enabled() {
        when(settings.getBoolean(SettingService.QUICK_COLLECT_ENABLED)).thenReturn(true);
        when(settings.get(eq(SettingService.CHAT_SYNC_CHAT_ID), any())).thenReturn("oc_1");
        when(settings.get(eq(SettingService.QUICK_COLLECT_PROJECT_ID), any())).thenReturn("");
        when(settings.get(eq(SettingService.CHAT_SYNC_APP_ID), any())).thenReturn("cli_chat");
        when(settings.get(eq(SettingService.CHAT_SYNC_APP_SECRET), any())).thenReturn("secret");
        ProjectDto project = mock(ProjectDto.class);
        when(project.id()).thenReturn("p1");
        when(project.name()).thenReturn("Wa All in One");
        when(projects.list("wd")).thenReturn(List.of(project));
        AccountIdentityEntity identity = new AccountIdentityEntity();
        identity.setAccountId("a1");
        AccountEntity account = new AccountEntity();
        account.setId("a1");
        account.setMemberId("wd");
        when(identities.findByProviderSubject("feishu", "ou_sender")).thenReturn(identity);
        when(identities.findByProviderUnionSubject("feishu", "on_sender")).thenReturn(identity);
        when(feishu.tenantAccessToken("cli_chat", "secret")).thenReturn("token");
        when(feishu.chatMemberUnionId("token", "oc_1", "ou_sender")).thenReturn("on_sender");
        when(accounts.selectById("a1")).thenReturn(account);
        when(events.insert(any(FeishuQuickCollectEventEntity.class))).thenReturn(1);
        when(tasks.createFromChat(any(), any(), any(), any(), any()))
                .thenReturn(new TaskDto("t9", "记一下", "p1", "待办", "P2", "wd",
                        List.of(), null, null, null, "", List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void urlVerificationEchoesTheChallenge() {
        var out = service.handleEvent("{\"type\":\"url_verification\",\"challenge\":\"abc123\",\"token\":\"real-token\"}");

        assertThat(out).containsEntry("challenge", "abc123");
    }

    @Test
    void wrongVerificationTokenIsRejected() {
        when(settings.get(SettingService.QUICK_COLLECT_VERIFY_TOKEN)).thenReturn("real-token");

        assertThatThrownBy(() -> service.handleEvent(
                "{\"type\":\"url_verification\",\"challenge\":\"abc\",\"token\":\"fake\"}"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void linkPreviewUsesTheDedicatedReadOnlyPathWithoutEnteringMessageHandlers() {
        Map<String, Object> preview = Map.of("inline", Map.of("title", "WAO 任务 · 整理证件盒"));
        when(linkPreviews.preview(any(), eq("https://wao.example"))).thenReturn(preview);

        var out = service.handleEvent("""
                {
                  "schema":"2.0",
                  "header":{"app_id":"cli_chat","event_type":"url.preview.get","token":"real-token"},
                  "event":{"context":{"url":"https://wao.example/s/t/t1"}}
                }
                """, "https://wao.example");

        assertThat(out).isEqualTo(preview);
        verify(linkPreviews).preview(any(), eq("https://wao.example"));
        verify(reactionAiRuns, never()).trigger(any(), any());
    }

    @Test
    void cardActionShowsIdentityResultWithoutAddingBotReaction() {
        when(feedback.collect(any())).thenReturn(new NotificationFeedbackService.Result("做到了", true));
        var out = service.handleEvent("""
                {"header":{"app_id":"cli_chat","event_type":"card.action.trigger","token":"real-token"},"event":{}}
                """);
        verify(feedback).collect(any());
        verify(feishu, never()).addMessageReaction(any(), any(), any());
        assertThat(out).containsEntry("toast", Map.of("type", "success", "content", "已记录：做到了，已用你的身份贴表情"));
    }

    @Test
    void savedFeedbackReportsRetryWhenIdentityCardCannotBeDisplayed() {
        when(feedback.collect(any())).thenReturn(new NotificationFeedbackService.Result("没做到", false));
        var out = service.handleEvent("""
                {"header":{"app_id":"cli_chat","event_type":"card.action.trigger","token":"real-token"},"event":{}}
                """);
        verify(feishu, never()).addMessageReaction(any(), any(), any());
        assertThat(out).containsEntry("toast", Map.of("type", "warning", "content", "已记录：没做到；个人表情暂未显示，请重试或重新授权"));
    }

    @Test
    void invalidFeedbackDoesNotClaimItWasRecorded() {
        var out = service.handleEvent("""
                {"header":{"app_id":"cli_chat","event_type":"card.action.trigger","token":"real-token"},"event":{}}
                """);
        verify(feishu, never()).addMessageReaction(any(), any(), any());
        assertThat(out).containsEntry("toast", Map.of("type", "warning", "content", "反馈未记录，请稍后重试"));
    }

    @Test
    void verifiedFlightReactionDispatchesAndStillReachesTheSharedChatBridge() {
        enabled();
        when(settings.get(SettingService.QUICK_COLLECT_VERIFY_TOKEN)).thenReturn("real-token");
        when(reactionAiRuns.trigger(any(), eq("https://wao.example"))).thenReturn(true);

        service.handleEvent(reaction("real-token"), "https://wao.example");

        verify(reactionAiRuns).trigger(any(), eq("https://wao.example"));
    }

    @Test
    void unverifiedReactionCannotDispatchAnAiRun() {
        enabled();

        assertThatThrownBy(() -> service.handleEvent(reaction(""), "https://wao.example"))
                .isInstanceOf(BadRequestException.class);

        verify(reactionAiRuns, never()).trigger(any(), any());
    }

    @Test
    void configuredEncryptKeyDoesNotTrustAPlaintextReaction() {
        enabled();
        when(settings.get(SettingService.QUICK_COLLECT_ENCRYPT_KEY)).thenReturn("my-encrypt-key");

        assertThatThrownBy(() -> service.handleEvent(reaction(""), "https://wao.example"))
                .isInstanceOf(BadRequestException.class);

        verify(reactionAiRuns, never()).trigger(any(), any());
    }

    @Test
    void encryptedReactionCanDispatchWithoutASeparateVerificationToken() throws Exception {
        enabled();
        when(settings.get(SettingService.QUICK_COLLECT_ENCRYPT_KEY)).thenReturn("my-encrypt-key");
        when(reactionAiRuns.trigger(any(), eq("https://wao.example"))).thenReturn(true);
        when(settings.get(SettingService.QUICK_COLLECT_VERIFY_TOKEN)).thenReturn("");
        String encrypted = encrypt(reaction(""), "my-encrypt-key");

        service.handleEvent("{\"encrypt\":\"" + encrypted + "\"}", "https://wao.example");

        verify(reactionAiRuns).trigger(any(), eq("https://wao.example"));
    }

    @Test
    void mentioningTheBotCreatesATaggedTask() {
        enabled();

        service.handleEvent(message("ev1", "text",
                "{\\\"text\\\":\\\"@_user_1 做一个群里 @ 机器人就能建任务的功能\\\"}"));

        verify(tasks).createFromChat(eq("p1"), eq("做一个群里 @ 机器人就能建任务的功能"),
                eq("做一个群里 @ 机器人就能建任务的功能"), eq("wd"), eq(FeishuQuickCollectService.SOURCE_TAG));
        verify(feishu, never()).listMessageReactions(any(), any(), any());
    }

    @Test
    void recalledMessageDeletesItsQuickCaptureEvenAfterCollectorWasDisabled() {
        when(settings.get(SettingService.CHAT_SYNC_CHAT_ID, "")).thenReturn("oc_1");

        service.handleEvent(recalled("ev-recall", "om_source", "oc_1"));

        verify(recalls).recall("ev-recall", "om_source", "oc_1");
        verify(tasks, never()).createFromChat(any(), any(), any(), any(), any());
    }

    @Test
    void recalledMessageFromAnotherChatCannotDeleteATask() {
        when(settings.get(SettingService.CHAT_SYNC_CHAT_ID, "")).thenReturn("oc_1");

        service.handleEvent(recalled("ev-recall-other", "om_source", "oc_other"));

        verify(recalls, never()).recall(any(), any(), any());
    }

    @Test
    void successfulCaptureAddsGetReactionWithoutSendingAGroupCard() {
        enabled();
        reactionsEnabled();

        service.handleEvent(message("ev-reaction", "text",
                "{\\\"text\\\":\\\"@_user_1 记录任务：整理方案\\\"}"));

        verify(feishu).addMessageReaction("token", "om_ev-reaction", "GET");
        verify(feishu, never()).sendCard(any(), any(), any());
    }

    @Test
    void reactionFailureDoesNotUndoTheCreatedTask() {
        enabled();
        reactionsEnabled();
        doThrow(new FeishuApiException("表情回执失败"))
                .when(feishu).addMessageReaction("token", "om_ev-reaction-failed", "GET");

        service.handleEvent(message("ev-reaction-failed", "text",
                "{\\\"text\\\":\\\"@_user_1 记录任务：整理方案\\\"}"));

        verify(tasks).createFromChat("p1", "整理方案", "整理方案", "wd",
                FeishuQuickCollectService.SOURCE_TAG);
    }

    @Test
    void polledMessageUsesTheSamePersonalCapturePath() {
        enabled();

        service.collectPolled("oc_1", new FeishuMessage(
                "om_poll", "ou_sender", "user", "text",
                "{\"text\":\"@_user_1 记录任务：整理周报\"}", 1L, false,
                List.of(new FeishuMention("@_user_1", "ou_bot", "任务待办"))));

        verify(tasks).createFromChat("p1", "整理周报", "整理周报", "wd",
                FeishuQuickCollectService.SOURCE_TAG);
    }

    @Test
    void polledMessageUsesUnionIdAcrossDifferentFeishuApps() {
        enabled();
        when(identities.findByProviderSubject("feishu", "ou_sender")).thenReturn(null);
        AccountIdentityEntity identity = new AccountIdentityEntity();
        identity.setAccountId("a1");
        when(identities.findByProviderUnionSubject("feishu", "on_sender")).thenReturn(identity);
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("cli_chat");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("secret");
        when(feishu.tenantAccessToken("cli_chat", "secret")).thenReturn("token");
        when(feishu.chatMemberUnionId("token", "oc_1", "ou_sender")).thenReturn("on_sender");

        service.collectPolled("oc_1", new FeishuMessage(
                "om_cross_app", "ou_sender", "user", "text",
                "{\"text\":\"@_user_1 记录XXX测试\"}", 1L, false,
                List.of(new FeishuMention("@_user_1", "ou_bot", "任务待办"))));

        verify(tasks).createFromChat("p1", "记录XXX测试", "记录XXX测试", "wd",
                FeishuQuickCollectService.SOURCE_TAG);
    }

    @Test
    void dedicatedNoteBotCreatesANoteForTheRealSenderWithoutCreatingATask() {
        enabled();
        service.collectPolled("oc_1", new FeishuMessage(
                "om_note", "ou_sender", "user", "text",
                "{\"text\":\"@_user_1 今天想到一个新方向\\n先把灵感记下来\"}", 1L, false,
                List.of(new FeishuMention("@_user_1", "cli_note", "小记创作"))));

        var request = org.mockito.ArgumentCaptor.forClass(CreateNoteRequest.class);
        verify(knowledge).createNote(org.mockito.ArgumentMatchers.matches("fn[a-f0-9]{30}"),
                request.capture(), eq("wd"));
        assertThat(request.getValue().title()).isEmpty();
        assertThat(request.getValue().blocks().get(0).p())
                .isEqualTo("今天想到一个新方向\n先把灵感记下来");
        verify(events).finishUnlessRecalled(eq("message:om_note"), eq("CREATED"), eq("NOTE"),
                org.mockito.ArgumentMatchers.matches("fn[a-f0-9]{30}"), any(), eq(null), any());
        verify(tasks, never()).createFromChat(any(), any(), any(), any(), any());
    }

    @Test
    void unboundSenderCannotCreateANoteUnderAnotherMembersName() {
        enabled();
        when(identities.findByProviderUnionSubject("feishu", "on_sender")).thenReturn(null);
        when(identities.findByProviderSubject("feishu", "ou_sender")).thenReturn(null);
        service.collectPolled("oc_1", new FeishuMessage(
                "om_unbound_note", "ou_sender", "user", "text",
                "{\"text\":\"@_user_1 私人小记\"}", 1L, false,
                List.of(new FeishuMention("@_user_1", "cli_note", "小记创作"))));

        verify(knowledge, never()).createNote(any(), any(), any());
        verify(events).finishUnlessRecalled(eq("message:om_unbound_note"), eq("REJECTED"), eq("NOTE"),
                eq(null), any(), eq("SENDER_NOT_BOUND"), any());
    }

    @Test
    void duplicateNoteMessageIsIgnoredAndAnEditUpdatesTheSameNote() {
        enabled();
        AtomicReference<FeishuQuickCollectEventEntity> stored = new AtomicReference<>();
        when(events.insert(any(FeishuQuickCollectEventEntity.class))).thenAnswer(invocation -> {
            if (stored.get() != null) throw new DuplicateKeyException("duplicate");
            stored.set(invocation.getArgument(0));
            return 1;
        });
        when(events.selectById("message:om_note_edit")).thenAnswer(invocation -> stored.get());
        when(events.claimChangedMessage(any(), any(), any(LocalDateTime.class))).thenReturn(1);
        when(events.finishUnlessRecalled(any(), any(), any(), any(), any(), any(), any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    var event = stored.get();
                    event.setStatus(invocation.getArgument(1));
                    event.setCaptureKind(invocation.getArgument(2));
                    event.setTargetId(invocation.getArgument(3));
                    event.setSourceFingerprint(invocation.getArgument(4));
                    return 1;
                });
        FeishuMessage first = noteMessage("om_note_edit", "第一段");
        FeishuMessage edited = noteMessage("om_note_edit", "更新后的内容");

        service.collectPolled("oc_1", first);
        service.collectPolled("oc_1", first);
        service.collectPolled("oc_1", edited);

        String id = stored.get().getTargetId();
        verify(knowledge, org.mockito.Mockito.times(1)).createNote(eq(id),
                org.mockito.ArgumentMatchers.argThat(req -> req.title().isEmpty() && "第一段".equals(req.blocks().get(0).p())), eq("wd"));
        verify(knowledge, org.mockito.Mockito.times(1)).createNote(eq(id),
                org.mockito.ArgumentMatchers.argThat(req -> req.title().isEmpty() && "更新后的内容".equals(req.blocks().get(0).p())), eq("wd"));
        verify(tasks, never()).createFromChat(any(), any(), any(), any(), any());
    }

    private static FeishuMessage noteMessage(String id, String text) {
        return new FeishuMessage(id, "ou_sender", "user", "text",
                "{\"text\":\"@_user_1 " + text + "\"}", 1L, false,
                List.of(new FeishuMention("@_user_1", "cli_note", "小记创作")));
    }

    @Test
    void dedicatedTaskBotUsesTheConfiguredDefaultProjectAndSender() {
        enabled();
        service.collectPolled("oc_1", new FeishuMessage(
                "om_task_bot", "ou_sender", "user", "text",
                "{\"text\":\"@_user_1 整理发布清单\"}", 1L, false,
                List.of(new FeishuMention("@_user_1", "cli_task", "任务待办"))));

        verify(tasks).createFromChat("p1", "整理发布清单", "整理发布清单", "wd",
                FeishuQuickCollectService.SOURCE_TAG);
        verify(knowledge, never()).createNote(any(), any(), any());
    }

    @Test
    void mentioningBothDedicatedBotsDoesNotGuessTheDestination() {
        enabled();
        service.collectPolled("oc_1", new FeishuMessage(
                "om_two_bots", "ou_sender", "user", "text",
                "{\"text\":\"@_user_1 @_user_2 内容\"}", 1L, false,
                List.of(new FeishuMention("@_user_1", "cli_task", "任务待办"),
                        new FeishuMention("@_user_2", "cli_note", "小记创作"))));

        verify(events, never()).insert(any(FeishuQuickCollectEventEntity.class));
        verify(tasks, never()).createFromChat(any(), any(), any(), any(), any());
        verify(knowledge, never()).createNote(any(), any(), any());
    }

    @Test
    void ordinaryGroupConversationWithoutMentionIsIgnored() {
        enabled();
        service.handleEvent(message("ev-normal", "text", "{\\\"text\\\":\\\"这是普通群聊\\\"}", false));
        verify(events, never()).insert(any(FeishuQuickCollectEventEntity.class));
        verify(tasks, never()).createFromChat(any(), any(), any(), any(), any());
    }

    @Test
    void ideaPrefixIsStrippedButStillCreatesAPersonalTask() {
        enabled();
        service.handleEvent(message("ev-idea", "text",
                "{\\\"text\\\":\\\"@_user_1 帮我记录想法：做一个每日面板\\\"}"));
        verify(tasks).createFromChat(eq("p1"), eq("做一个每日面板"), eq("做一个每日面板"), eq("wd"),
                eq(FeishuQuickCollectService.IDEA_SOURCE_TAG));
    }

    @Test
    void unboundSenderGetsBindingGuideAndNoTaskIsCreated() {
        enabled();
        reactionsEnabled();
        when(identities.findByProviderSubject("feishu", "ou_sender")).thenReturn(null);
        service.handleEvent(message("ev-unbound", "text",
                "{\\\"text\\\":\\\"@_user_1 记录任务：整理方案\\\"}"));
        verify(tasks, never()).createFromChat(any(), any(), any(), any(), any());
        verify(feishu).addMessageReaction("token", "om_ev-unbound", "ERROR");
        verify(feishu).sendTextMessage(eq("token"), eq("oc_1"),
                org.mockito.ArgumentMatchers.contains("/settings?tab=account"), any(), eq("om_ev-unbound"));
    }

    @Test
    void repeatedDeliveryOfTheSameEventCreatesOneTask() {
        enabled();
        AtomicBoolean claimed = new AtomicBoolean();
        AtomicReference<FeishuQuickCollectEventEntity> stored = new AtomicReference<>();
        when(events.insert(any(FeishuQuickCollectEventEntity.class))).thenAnswer(invocation -> {
            if (!claimed.compareAndSet(false, true)) {
                throw new DuplicateKeyException("duplicate");
            }
            stored.set(invocation.getArgument(0));
            return 1;
        });
        when(events.selectById(any())).thenAnswer(invocation -> stored.get());
        String body = message("ev2", "text", "{\\\"text\\\":\\\"再想到一个点子\\\"}");

        service.handleEvent(body);
        service.handleEvent(body);

        verify(tasks).createFromChat(any(), any(), any(), any(), any());
    }

    @Test
    void callbackAndPollingShareMessageIdDeduplication() {
        enabled();
        AtomicBoolean claimed = new AtomicBoolean();
        AtomicReference<FeishuQuickCollectEventEntity> stored = new AtomicReference<>();
        when(events.insert(any(FeishuQuickCollectEventEntity.class))).thenAnswer(invocation -> {
            if (!claimed.compareAndSet(false, true)) {
                throw new DuplicateKeyException("duplicate");
            }
            stored.set(invocation.getArgument(0));
            return 1;
        });
        when(events.selectById(any())).thenAnswer(invocation -> stored.get());

        service.handleEvent(message("ev-both", "text",
                "{\\\"text\\\":\\\"@_user_1 记录任务：同一条消息\\\"}"));
        service.collectPolled("oc_1", new FeishuMessage(
                "om_ev-both", "ou_sender", "user", "text",
                "{\"text\":\"@_user_1 记录任务：同一条消息\"}", 1L, false,
                List.of(new FeishuMention("@_user_1", "ou_bot", "任务待办"))));

        verify(tasks, org.mockito.Mockito.times(1))
                .createFromChat(any(), any(), any(), any(), any());
        assertThat(stored.get().getEventKey()).isEqualTo("message:om_ev-both");
    }

    @Test
    void editedMessageUpdatesTheOriginalTaskInsteadOfCreatingAnotherOne() {
        enabled();
        AtomicBoolean claimed = new AtomicBoolean();
        AtomicReference<FeishuQuickCollectEventEntity> stored = new AtomicReference<>();
        when(events.insert(any(FeishuQuickCollectEventEntity.class))).thenAnswer(invocation -> {
            if (!claimed.compareAndSet(false, true)) {
                throw new DuplicateKeyException("duplicate");
            }
            stored.set(invocation.getArgument(0));
            return 1;
        });
        when(events.selectById(any())).thenAnswer(invocation -> stored.get());
        when(events.claimChangedMessage(any(), any(), any(LocalDateTime.class))).thenReturn(1);
        when(events.finishUnlessRecalled(any(), any(), any(), any(), any(), any(), any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    FeishuQuickCollectEventEntity event = stored.get();
                    event.setStatus(invocation.getArgument(1));
                    event.setCaptureKind(invocation.getArgument(2));
                    String targetId = invocation.getArgument(3);
                    if (targetId != null) event.setTargetId(targetId);
                    String fingerprint = invocation.getArgument(4);
                    if (fingerprint != null) event.setSourceFingerprint(fingerprint);
                    return 1;
                });
        when(tasks.updateFromChat(any(), any(), any(), any())).thenReturn(task("t9", "完善后的方案"));

        service.collectPolled("oc_1", polled("om_edit", "记录任务：先写方案"));
        service.collectPolled("oc_1", polled("om_edit", "记录任务：完善后的方案"));

        verify(tasks).createFromChat("p1", "先写方案", "先写方案", "wd",
                FeishuQuickCollectService.SOURCE_TAG);
        verify(tasks).updateFromChat("t9", "完善后的方案", "完善后的方案", "wd");
        verify(tasks, org.mockito.Mockito.times(1)).createFromChat(any(), any(), any(), any(), any());
    }

    @Test
    void recallThatWinsWhileTaskIsBeingCreatedDeletesTheLateTask() {
        enabled();
        FeishuQuickCollectEventEntity recalled = new FeishuQuickCollectEventEntity();
        recalled.setEventKey("message:om_ev-race");
        recalled.setStatus("RECALLING");
        when(events.selectById("message:om_ev-race")).thenReturn(recalled);
        when(events.finishUnlessRecalled(any(), any(), any(), any(), any(), any(), any(LocalDateTime.class)))
                .thenReturn(0);

        service.handleEvent(message("ev-race", "text",
                "{\\\"text\\\":\\\"@_user_1 记录任务：撤回竞态\\\"}"));

        verify(tasks).deleteFromChat("t9");
        verify(events).attachRecalledTarget(eq("message:om_ev-race"), eq("t9"), any(),
                any(LocalDateTime.class));
        verify(events).completeRecall(eq("message:om_ev-race"), any(LocalDateTime.class));
    }

    @Test
    void richTextImagesAreStoredAndRenderedInTheTaskDescription() {
        enabled();
        reactionsEnabled();
        when(feishu.downloadImage("token", "om_image", "img_key")).thenReturn(new byte[]{1, 2, 3});
        when(images.store(any(), eq("image/png"), eq("img_key.png"), eq("飞书任务 · om_image"), eq(true)))
                .thenReturn(new ImageItemDto("img1", "img_key.png", "3 B", "", "",
                        "/api/images/img1/raw", "", ""));

        service.collectPolled("oc_1", new FeishuMessage(
                "om_image", "ou_sender", "user", "post",
                "{\"title\":\"\",\"content\":[[{\"tag\":\"text\",\"text\":\"@_user_1 记录任务：看设计图\"},"
                        + "{\"tag\":\"img\",\"image_key\":\"img_key\"}]]}",
                1L, false, List.of(new FeishuMention("@_user_1", "ou_bot", "任务待办"))));

        verify(tasks).createFromChat("p1", "看设计图",
                "看设计图\n\n![任务图片](/api/images/img1/raw)", "wd",
                FeishuQuickCollectService.SOURCE_TAG);
    }

    @Test
    void imageBeforeTextIsMovedBehindTextAndDoesNotPolluteTheTitle() {
        enabled();
        reactionsEnabled();
        when(feishu.downloadImage("token", "om_image_first", "img_key")).thenReturn(new byte[]{1, 2, 3});
        when(images.store(any(), eq("image/png"), eq("img_key.png"), eq("飞书任务 · om_image_first"), eq(true)))
                .thenReturn(new ImageItemDto("img1", "img_key.png", "3 B", "", "",
                        "/api/images/img1/raw", "", ""));

        service.collectPolled("oc_1", new FeishuMessage(
                "om_image_first", "ou_sender", "user", "post",
                "{\"title\":\"\",\"content\":[[{\"tag\":\"img\",\"image_key\":\"img_key\"}],"
                        + "[{\"tag\":\"text\",\"text\":\"@_user_1 记录任务：核对新版布局\"}]]}",
                1L, false, List.of(new FeishuMention("@_user_1", "ou_bot", "任务待办"))));

        verify(tasks).createFromChat("p1", "核对新版布局",
                "核对新版布局\n\n![任务图片](/api/images/img1/raw)", "wd",
                FeishuQuickCollectService.SOURCE_TAG);
    }

    @Test
    void imageOnlyMessageUsesReadableTitleAndKeepsAllImagesAtTheEnd() {
        enabled();
        reactionsEnabled();
        when(feishu.downloadImage(eq("token"), eq("om_images"), any())).thenReturn(new byte[]{1, 2, 3});
        when(images.store(any(), eq("image/png"), eq("img_a.png"), eq("飞书任务 · om_images"), eq(true)))
                .thenReturn(new ImageItemDto("img1", "img_a.png", "3 B", "", "",
                        "/api/images/img1/raw", "", ""));
        when(images.store(any(), eq("image/png"), eq("img_b.png"), eq("飞书任务 · om_images"), eq(true)))
                .thenReturn(new ImageItemDto("img2", "img_b.png", "3 B", "", "",
                        "/api/images/img2/raw", "", ""));

        service.collectPolled("oc_1", new FeishuMessage(
                "om_images", "ou_sender", "user", "post",
                "{\"title\":\"\",\"content\":[[{\"tag\":\"text\",\"text\":\"@_user_1\"},"
                        + "{\"tag\":\"img\",\"image_key\":\"img_a\"}],"
                        + "[{\"tag\":\"img\",\"image_key\":\"img_b\"}]]}",
                1L, false, List.of(new FeishuMention("@_user_1", "ou_bot", "任务待办"))));

        verify(tasks).createFromChat("p1", "图片待办",
                "![任务图片](/api/images/img1/raw)\n\n![任务图片](/api/images/img2/raw)", "wd",
                FeishuQuickCollectService.SOURCE_TAG);
    }

    @Test
    void voiceMessagesAreAnsweredWithAHintInsteadOfAFakeTranscript() {
        enabled();
        reactionsEnabled();

        service.handleEvent(message("ev3", "audio", "{\\\"file_key\\\":\\\"f1\\\"}"));

        verify(tasks, never()).createFromChat(any(), any(), any(), any(), any());
        verify(feishu).addMessageReaction("token", "om_ev3", "ERROR");
    }

    @Test
    void encryptedEventsAreDecryptedBeforeBeingRead() throws Exception {
        when(settings.get(SettingService.QUICK_COLLECT_VERIFY_TOKEN)).thenReturn("");
        when(settings.get(SettingService.QUICK_COLLECT_ENCRYPT_KEY)).thenReturn("my-encrypt-key");

        var out = service.handleEvent("{\"encrypt\":\""
                + encrypt("{\"type\":\"url_verification\",\"challenge\":\"enc-ok\"}", "my-encrypt-key") + "\"}");

        assertThat(out).containsEntry("challenge", "enc-ok");
    }

    @Test
    void titleFallsBackToTheFirstLineAndIsTruncated() {
        assertThat(FeishuQuickCollectService.title("第一行\n第二行")).isEqualTo("第一行");
        assertThat(FeishuQuickCollectService.title("![任务图片](/api/images/img1/raw)\n第二行"))
                .isEqualTo("第二行");
        assertThat(FeishuQuickCollectService.title("![任务图片](/api/images/img1/raw)"))
                .isEqualTo("图片待办");
        assertThat(FeishuQuickCollectService.title("一".repeat(60))).hasSize(41).endsWith("…");
    }

    @Test
    void htmlParagraphsFromRichTextBecomeAReadableFirstParagraphTitle() {
        assertThat(FeishuQuickCollectService.title(
                "<p>测试修改问题，任务那边会不会同步更新</p><p></p><p>更新了吗？</p>"))
                .isEqualTo("测试修改问题，任务那边会不会同步更新");
        assertThat(FeishuQuickCollectService.title(
                "<div><strong>修复 &amp; 验证</strong><br>不要进入标题</div>"))
                .isEqualTo("修复 & 验证");
    }

    @Test
    void richTextHtmlStillKeepsItsOriginalDescriptionWhileCreatingACleanTitle() {
        enabled();
        String html = "<p>测试修改问题，任务那边会不会同步更新</p><p></p><p>更新了吗？</p>";

        service.collectPolled("oc_1", new FeishuMessage(
                "om_html", "ou_sender", "user", "post",
                "{\"title\":\"\",\"content\":[[{\"tag\":\"text\",\"text\":\"@_user_1 "
                        + html.replace("\"", "\\\"") + "\"}]]}",
                1L, false, List.of(new FeishuMention("@_user_1", "ou_bot", "任务待办"))));

        verify(tasks).createFromChat("p1", "测试修改问题，任务那边会不会同步更新", html, "wd",
                FeishuQuickCollectService.SOURCE_TAG);
    }

    @Test
    void emptyCommandDoesNotCreateAnEmptyTask() {
        enabled();
        service.handleEvent(message("ev-empty", "text",
                "{\\\"text\\\":\\\"@_user_1 记录任务：\\\"}"));
        verify(tasks, never()).createFromChat(any(), any(), any(), any(), any());
    }

    private void reactionsEnabled() {
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("cli_chat");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("secret");
        when(feishu.tenantAccessToken("cli_chat", "secret")).thenReturn("token");
    }

    @Test
    void textKeepsOtherPeopleMentionsAndDoesNotConfuseOverlappingPlaceholderKeys() {
        enabled();
        service.collectPolled("oc_1", new FeishuMessage("om_people", "ou_sender", "user", "text",
                "{\"text\":\"@_user_1 提醒 @_user_10：确认安排\"}", 1L, false,
                List.of(new FeishuMention("@_user_1", "ou_bot", "任务待办"),
                        new FeishuMention("@_user_10", "ou_person", "小王"))));
        verify(tasks).createFromChat("p1", "提醒 @小王：确认安排", "提醒 @小王：确认安排", "wd",
                FeishuQuickCollectService.SOURCE_TAG);
    }

    @Test
    void richTextAtSpansRemoveOnlyEntryBotAndPreserveActualHumanName() {
        enabled();
        service.collectPolled("oc_1", new FeishuMessage("om_post_people", "ou_sender", "user", "post",
                """
                {"title":"","content":[[{"tag":"at","user_id":"ou_bot"},
                {"tag":"text","text":"和 "},{"tag":"at","user_id":"ou_person"},
                {"tag":"text","text":"一起确认"}]]}
                """, 1L, false,
                List.of(new FeishuMention("@_user_1", "ou_bot", "任务待办"),
                        new FeishuMention("@_user_2", "ou_person", "小王"))));
        verify(tasks).createFromChat("p1", "和 @小王一起确认", "和 @小王一起确认", "wd",
                FeishuQuickCollectService.SOURCE_TAG);
    }

    @Test
    void configuredStableBotIdDoesNotRemoveSameNamedHumanMention() {
        enabled();
        var registry = mock(FeishuWebhookConfigService.class);
        when(registry.captureRoute(any())).thenAnswer(invocation -> {
            com.fasterxml.jackson.databind.JsonNode mentions = invocation.getArgument(0);
            for (var mention : mentions)
                if ("ou_bot".equals(mention.path("id").path("open_id").asText()))
                    return new FeishuWebhookConfigService.CaptureRoute("fw1", "TASK");
            return null;
        });
        service.setBotCenter(registry, null, null);
        service.collectPolled("oc_1", new FeishuMessage("om_same_name", "ou_sender", "user", "text",
                "{\"text\":\"@_user_1 提醒 @_user_2 确认\"}", 1L, false,
                List.of(new FeishuMention("@_user_1", "ou_bot", "任务待办"),
                        new FeishuMention("@_user_2", "ou_person", "任务待办"))));
        verify(tasks).createFromChat("p1", "提醒 @任务待办 确认", "提醒 @任务待办 确认", "wd",
                FeishuQuickCollectService.SOURCE_TAG);
    }

    @Test
    void directPolledBindingStillChecksRealUnionIdAndRejectsAccountConflict() {
        enabled();
        var other = new AccountIdentityEntity(); other.setAccountId("a2");
        when(feishu.chatMemberUnionId("token", "oc_1", "ou_sender")).thenReturn("on_other");
        when(identities.findByProviderUnionSubject("feishu", "on_other")).thenReturn(other);
        service.collectPolled("oc_1", polled("om_poll_conflict", "内容"));
        verify(feishu).chatMemberUnionId("token", "oc_1", "ou_sender");
        verify(tasks, never()).createFromChat(any(), any(), any(), any(), any());
        verify(events).finishUnlessRecalled(eq("message:om_poll_conflict"), eq("REJECTED"), eq("TASK"),
                eq(null), any(), eq("SENDER_IDENTITY_CONFLICT"), any());
    }

    @Test
    void failedOrAmbiguousPolledUnionLookupCannotFallBackToDirectBinding() {
        enabled();
        when(feishu.chatMemberUnionId("token", "oc_1", "ou_sender"))
                .thenThrow(new IllegalStateException("temporary")).thenReturn("");
        assertThatThrownBy(() -> service.collectPolled("oc_1", polled("om_union_api", "内容")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.collectPolled("oc_1", polled("om_union_missing", "内容")))
                .isInstanceOf(BadRequestException.class);
        verify(events, never()).insert(any(FeishuQuickCollectEventEntity.class));
        verify(tasks, never()).createFromChat(any(), any(), any(), any(), any());
    }

    @Test
    void productionPollingPersistsMessageBeforeUnionLookupSoFailuresCanRetry() {
        enabled();
        var registry = mock(FeishuWebhookConfigService.class);
        var inbox = mock(FeishuBotInboxService.class);
        service.setBotCenter(registry, inbox, null);
        service.collectPolled("oc_1", polled("om_pending_identity", "内容"));
        var event = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(inbox).enqueue(event.capture(), eq(""), eq("cli_chat"));
        assertThat(event.getValue().path("_wao_polled_identity_check").asBoolean()).isTrue();
        verify(feishu, never()).chatMemberUnionId(any(), any(), any());
        verify(tasks, never()).createFromChat(any(), any(), any(), any(), any());
    }

    @Test
    void unknownMentionCannotFallBackToATask() {
        enabled();
        service.handleEvent(message("unknown", "text", "{\\\"text\\\":\\\"@_user_1 私聊内容\\\"}")
                .replace("任务待办", "普通成员"));
        verify(events, never()).insert(any(FeishuQuickCollectEventEntity.class));
        verify(tasks, never()).createFromChat(any(), any(), any(), any(), any());
    }

    @Test
    void emptyConfiguredChatAndBotSenderAreRejected() {
        enabled();
        when(settings.get(SettingService.CHAT_SYNC_CHAT_ID, "")).thenReturn("");
        service.handleEvent(message("empty-chat", "text", "{\\\"text\\\":\\\"@_user_1 内容\\\"}"));
        when(settings.get(SettingService.CHAT_SYNC_CHAT_ID, "")).thenReturn("oc_1");
        service.handleEvent(message("bot-sender", "text", "{\\\"text\\\":\\\"@_user_1 内容\\\"}")
                .replace("\"sender_type\":\"user\"", "\"sender_type\":\"app\""));
        verify(events, never()).insert(any(FeishuQuickCollectEventEntity.class));
    }

    @Test
    void callbackMustHaveAuthenticationAndTheConfiguredApplication() {
        enabled();
        when(settings.get(SettingService.QUICK_COLLECT_VERIFY_TOKEN)).thenReturn("");
        assertThatThrownBy(() -> service.handleEvent(message("unsafe", "text", "{}")))
                .isInstanceOf(BadRequestException.class);
        when(settings.get(SettingService.QUICK_COLLECT_VERIFY_TOKEN)).thenReturn("real-token");
        assertThatThrownBy(() -> service.handleEvent(message("wrong-app", "text", "{}")
                .replace("cli_chat", "cli_other"))).isInstanceOf(BadRequestException.class);
        verify(tasks, never()).createFromChat(any(), any(), any(), any(), any());
    }

    @Test
    void conflictingOpenAndUnionIdentitiesCannotAttributeAPersonalTask() {
        enabled();
        var other = new AccountIdentityEntity(); other.setAccountId("someone-else");
        when(identities.findByProviderUnionSubject("feishu", "on_conflict")).thenReturn(other);
        service.handleEvent(message("conflict", "text", "{\\\"text\\\":\\\"@_user_1 内容\\\"}")
                .replace("\"open_id\":\"ou_sender\"", "\"open_id\":\"ou_sender\",\"union_id\":\"on_conflict\""));
        verify(tasks, never()).createFromChat(any(), any(), any(), any(), any());
        verify(events).finishUnlessRecalled(eq("message:om_conflict"), eq("REJECTED"), eq("TASK"),
                eq(null), any(), eq("SENDER_IDENTITY_CONFLICT"), any());
    }

    @Test
    void authenticatedCallbackAcknowledgesDurableDepositWithoutCreatingInline() {
        enabled();
        var registry = mock(FeishuWebhookConfigService.class);
        var inbox = mock(FeishuBotInboxService.class);
        service.setBotCenter(registry, inbox, mock(FeishuCaptureTargetGuard.class));
        assertThat(service.handleEvent(message("queued", "text", "{}"))).isEmpty();
        verify(inbox).enqueue(any(), eq("queued"), eq("cli_chat"));
        verify(tasks, never()).createFromChat(any(), any(), any(), any(), any());
    }

    @Test
    void disablingRouteAfterDepositCannotCreateOrCompleteQueuedCapture() throws Exception {
        enabled();
        var registry = mock(FeishuWebhookConfigService.class);
        var inbox = mock(FeishuBotInboxService.class);
        service.setBotCenter(registry, inbox, mock(FeishuCaptureTargetGuard.class));
        var row = new cn.wayuwa.wao.domain.FeishuBotInboxEntity();
        row.setAppId("cli_chat"); row.setId("queued"); row.setWebhookId("fw1"); row.setCaptureKind("TASK");
        when(inbox.event(row)).thenReturn(new ObjectMapper().readTree(message("queued", "text", "{}")).path("event"));
        assertThatThrownBy(() -> service.processInbox(row)).isInstanceOf(BadRequestException.class);
        verify(inbox, never()).complete(any());
        verify(tasks, never()).createFromChat(any(), any(), any(), any(), any());
    }

    private static FeishuMessage polled(String messageId, String text) {
        return new FeishuMessage(messageId, "ou_sender", "user", "text",
                "{\"text\":\"@_user_1 " + text + "\"}", 1L, false,
                List.of(new FeishuMention("@_user_1", "ou_bot", "任务待办")));
    }

    private static TaskDto task(String id, String title) {
        return new TaskDto(id, title, "p1", "待办", "P2", "wd",
                List.of(), null, null, null, title, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** 一条 im.message.receive_v1 事件；content 是转义过的 JSON 字符串，与飞书原样一致。 */
    private static String message(String eventId, String type, String content) {
        return message(eventId, type, content, true);
    }

    private static String message(String eventId, String type, String content, boolean mentioned) {
        return "{\"schema\":\"2.0\",\"header\":{\"app_id\":\"cli_chat\",\"token\":\"real-token\",\"event_id\":\"" + eventId
                + "\",\"event_type\":\"im.message.receive_v1\"},\"event\":{"
                + "\"sender\":{\"sender_type\":\"user\",\"sender_id\":{\"open_id\":\"ou_sender\"}},"
                + "\"message\":{\"message_id\":\"om_" + eventId + "\",\"chat_id\":\"oc_1\",\"message_type\":\"" + type + "\","
                + "\"mentions\":" + (mentioned ? "[{\"key\":\"@_user_1\",\"name\":\"任务待办\"}]" : "[]") + ","
                + "\"content\":\"" + content + "\"}}}";
    }

    private static String reaction(String token) {
        return "{\"schema\":\"2.0\",\"header\":{\"app_id\":\"cli_chat\",\"token\":\"real-token\",\"event_id\":\"ev-flight\","
                + "\"event_type\":\"im.message.reaction.created_v1\",\"token\":\"" + token + "\"},"
                + "\"event\":{\"message_id\":\"om_source\",\"operator_type\":\"user\","
                + "\"user_id\":{\"open_id\":\"ou_sender\"},"
                + "\"reaction_type\":{\"emoji_type\":\"StatusInFlight\"},"
                + "\"action_time\":\"1787900000000\"}}";
    }

    private static String recalled(String eventId, String messageId, String chatId) {
        return "{\"schema\":\"2.0\",\"header\":{\"app_id\":\"cli_chat\",\"token\":\"real-token\",\"event_id\":\"" + eventId
                + "\",\"event_type\":\"im.message.recalled_v1\"},\"event\":{"
                + "\"message_id\":\"" + messageId + "\",\"chat_id\":\"" + chatId
                + "\",\"recall_type\":\"message_owner\"}}";
    }

    /** 按飞书的规则加密：Base64(IV + AES-256-CBC(SHA256(key)))。 */
    private static String encrypt(String plain, String key) throws Exception {
        byte[] secret = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(iv));
        byte[] body = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[iv.length + body.length];
        System.arraycopy(iv, 0, all, 0, iv.length);
        System.arraycopy(body, 0, all, iv.length, body.length);
        return Base64.getEncoder().encodeToString(all);
    }

}
