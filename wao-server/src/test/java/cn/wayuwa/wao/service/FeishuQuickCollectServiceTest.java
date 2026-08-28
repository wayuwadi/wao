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
import cn.wayuwa.wao.dto.ProjectDto;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.feishu.FeishuApiException;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuMention;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuMessage;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import cn.wayuwa.wao.mapper.FeishuQuickCollectEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class FeishuQuickCollectServiceTest {

    private final SettingService settings = mock(SettingService.class);
    private final FeishuOpenApiClient feishu = mock(FeishuOpenApiClient.class);
    private final TaskService tasks = mock(TaskService.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final AccountMapper accounts = mock(AccountMapper.class);
    private final AccountIdentityMapper identities = mock(AccountIdentityMapper.class);
    private final FeishuQuickCollectEventMapper events = mock(FeishuQuickCollectEventMapper.class);
    private final ImageService images = mock(ImageService.class);
    private final NotificationFeedbackService feedback = mock(NotificationFeedbackService.class);
    private final SharedChatFeishuBridge sharedChat = mock(SharedChatFeishuBridge.class);
    private final FeishuQuickCollectAiRunService reactionAiRuns = mock(FeishuQuickCollectAiRunService.class);
    private final FeishuQuickCollectService service = new FeishuQuickCollectService(
            settings, feishu, tasks, projects, accounts, identities, events, images, new ObjectMapper(), feedback,
            sharedChat, reactionAiRuns);

    private void enabled() {
        when(settings.getBoolean(SettingService.QUICK_COLLECT_ENABLED)).thenReturn(true);
        when(settings.get(eq(SettingService.CHAT_SYNC_CHAT_ID), any())).thenReturn("");
        when(settings.get(eq(SettingService.QUICK_COLLECT_PROJECT_ID), any())).thenReturn("");
        when(settings.get(eq(SettingService.CHAT_SYNC_APP_ID), any())).thenReturn("");
        when(settings.get(eq(SettingService.CHAT_SYNC_APP_SECRET), any())).thenReturn("");
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
        when(accounts.selectById("a1")).thenReturn(account);
        when(events.insert(any(FeishuQuickCollectEventEntity.class))).thenReturn(1);
        when(tasks.createFromChat(any(), any(), any(), any(), any()))
                .thenReturn(new TaskDto("t9", "记一下", "p1", "待办", "P2", "wd",
                        List.of(), null, null, null, "", List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void urlVerificationEchoesTheChallenge() {
        var out = service.handleEvent("{\"type\":\"url_verification\",\"challenge\":\"abc123\"}");

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
    void verifiedFlightReactionDispatchesAndStillReachesTheSharedChatBridge() {
        enabled();
        when(settings.get(SettingService.QUICK_COLLECT_VERIFY_TOKEN)).thenReturn("real-token");
        when(reactionAiRuns.trigger(any(), eq("https://wao.example"))).thenReturn(true);

        service.handleEvent(reaction("real-token"), "https://wao.example");

        verify(reactionAiRuns).trigger(any(), eq("https://wao.example"));
        verify(sharedChat).handleEvent(any());
    }

    @Test
    void unverifiedReactionCannotDispatchAnAiRun() {
        enabled();

        service.handleEvent(reaction(""), "https://wao.example");

        verify(reactionAiRuns, never()).trigger(any(), any());
        verify(sharedChat).handleEvent(any());
    }

    @Test
    void configuredEncryptKeyDoesNotTrustAPlaintextReaction() {
        enabled();
        when(settings.get(SettingService.QUICK_COLLECT_ENCRYPT_KEY)).thenReturn("my-encrypt-key");

        service.handleEvent(reaction(""), "https://wao.example");

        verify(reactionAiRuns, never()).trigger(any(), any());
    }

    @Test
    void encryptedReactionCanDispatchWithoutASeparateVerificationToken() throws Exception {
        enabled();
        when(settings.get(SettingService.QUICK_COLLECT_ENCRYPT_KEY)).thenReturn("my-encrypt-key");
        when(reactionAiRuns.trigger(any(), eq("https://wao.example"))).thenReturn(true);
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
    }

    @Test
    void successfulCaptureAddsDoneReactionWithoutSendingAGroupCard() {
        enabled();
        reactionsEnabled();

        service.handleEvent(message("ev-reaction", "text",
                "{\\\"text\\\":\\\"@_user_1 记录任务：整理方案\\\"}"));

        verify(feishu).addMessageReaction("token", "om_ev-reaction", "DONE");
        verify(feishu, never()).sendCard(any(), any(), any());
    }

    @Test
    void reactionFailureDoesNotUndoTheCreatedTask() {
        enabled();
        reactionsEnabled();
        doThrow(new FeishuApiException("表情回执失败"))
                .when(feishu).addMessageReaction("token", "om_ev-reaction-failed", "DONE");

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
                List.of(new FeishuMention("@_user_1", "ou_bot", "WAO"))));

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
                List.of(new FeishuMention("@_user_1", "ou_bot", "WAO"))));

        verify(tasks).createFromChat("p1", "记录XXX测试", "记录XXX测试", "wd",
                FeishuQuickCollectService.SOURCE_TAG);
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
                List.of(new FeishuMention("@_user_1", "ou_bot", "WAO"))));

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
        when(tasks.updateFromChat(any(), any(), any(), any())).thenReturn(task("t9", "完善后的方案"));

        service.collectPolled("oc_1", polled("om_edit", "记录任务：先写方案"));
        service.collectPolled("oc_1", polled("om_edit", "记录任务：完善后的方案"));

        verify(tasks).createFromChat("p1", "先写方案", "先写方案", "wd",
                FeishuQuickCollectService.SOURCE_TAG);
        verify(tasks).updateFromChat("t9", "完善后的方案", "完善后的方案", "wd");
        verify(tasks, org.mockito.Mockito.times(1)).createFromChat(any(), any(), any(), any(), any());
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
                1L, false, List.of(new FeishuMention("@_user_1", "ou_bot", "WAO"))));

        verify(tasks).createFromChat("p1", "看设计图",
                "看设计图\n\n![飞书图片](/api/images/img1/raw)", "wd",
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
        when(settings.get(SettingService.QUICK_COLLECT_ENCRYPT_KEY)).thenReturn("my-encrypt-key");

        var out = service.handleEvent("{\"encrypt\":\""
                + encrypt("{\"type\":\"url_verification\",\"challenge\":\"enc-ok\"}", "my-encrypt-key") + "\"}");

        assertThat(out).containsEntry("challenge", "enc-ok");
    }

    @Test
    void titleFallsBackToTheFirstLineAndIsTruncated() {
        assertThat(FeishuQuickCollectService.title("第一行\n第二行")).isEqualTo("第一行");
        assertThat(FeishuQuickCollectService.title("一".repeat(60))).hasSize(41).endsWith("…");
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

    private static FeishuMessage polled(String messageId, String text) {
        return new FeishuMessage(messageId, "ou_sender", "user", "text",
                "{\"text\":\"@_user_1 " + text + "\"}", 1L, false,
                List.of(new FeishuMention("@_user_1", "ou_bot", "WAO")));
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
        return "{\"schema\":\"2.0\",\"header\":{\"event_id\":\"" + eventId
                + "\",\"event_type\":\"im.message.receive_v1\"},\"event\":{"
                + "\"sender\":{\"sender_id\":{\"open_id\":\"ou_sender\"}},"
                + "\"message\":{\"message_id\":\"om_" + eventId + "\",\"chat_id\":\"oc_1\",\"message_type\":\"" + type + "\","
                + "\"mentions\":" + (mentioned ? "[{\"key\":\"@_user_1\",\"name\":\"娃弟\"}]" : "[]") + ","
                + "\"content\":\"" + content + "\"}}}";
    }

    private static String reaction(String token) {
        return "{\"schema\":\"2.0\",\"header\":{\"event_id\":\"ev-flight\","
                + "\"event_type\":\"im.message.reaction.created_v1\",\"token\":\"" + token + "\"},"
                + "\"event\":{\"message_id\":\"om_source\",\"operator_type\":\"user\","
                + "\"user_id\":{\"open_id\":\"ou_sender\"},"
                + "\"reaction_type\":{\"emoji_type\":\"StatusInFlight\"},"
                + "\"action_time\":\"1787900000000\"}}";
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
