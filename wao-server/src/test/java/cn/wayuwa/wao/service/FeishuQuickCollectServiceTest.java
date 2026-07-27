package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class FeishuQuickCollectServiceTest {

    private final SettingService settings = mock(SettingService.class);
    private final FeishuOpenApiClient feishu = mock(FeishuOpenApiClient.class);
    private final FeishuWebhookNotifier notifier = mock(FeishuWebhookNotifier.class);
    private final TaskService tasks = mock(TaskService.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final AccountMapper accounts = mock(AccountMapper.class);
    private final AccountIdentityMapper identities = mock(AccountIdentityMapper.class);
    private final FeishuQuickCollectService service = new FeishuQuickCollectService(
            settings, feishu, notifier, tasks, projects, accounts, identities, new ObjectMapper());

    private void enabled() {
        when(settings.getBoolean(SettingService.QUICK_COLLECT_ENABLED)).thenReturn(true);
        when(settings.get(eq(SettingService.CHAT_SYNC_CHAT_ID), any())).thenReturn("");
        when(settings.get(eq(SettingService.QUICK_COLLECT_PROJECT_ID), any())).thenReturn("");
        when(settings.get(eq(SettingService.CHAT_SYNC_APP_ID), any())).thenReturn("");
        when(settings.get(eq(SettingService.CHAT_SYNC_APP_SECRET), any())).thenReturn("");
        when(projects.selectList(null)).thenReturn(List.of(project("p1", "Wa All in One")));
        when(tasks.createFromChat(any(), any(), any(), any(), any()))
                .thenReturn(new TaskDto("t9", "记一下", "p1", "待办", "P2", "wg",
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
    void mentioningTheBotCreatesATaggedTask() {
        enabled();

        service.handleEvent(message("ev1", "text",
                "{\\\"text\\\":\\\"@_user_1 做一个群里 @ 机器人就能建任务的功能\\\"}"));

        verify(tasks).createFromChat(eq("p1"), eq("做一个群里 @ 机器人就能建任务的功能"),
                eq("做一个群里 @ 机器人就能建任务的功能"), eq("wg"), eq(FeishuQuickCollectService.SOURCE_TAG));
    }

    @Test
    void repeatedDeliveryOfTheSameEventCreatesOneTask() {
        enabled();
        String body = message("ev2", "text", "{\\\"text\\\":\\\"再想到一个点子\\\"}");

        service.handleEvent(body);
        service.handleEvent(body);

        verify(tasks).createFromChat(any(), any(), any(), any(), any());
    }

    @Test
    void voiceMessagesAreAnsweredWithAHintInsteadOfAFakeTranscript() {
        enabled();

        service.handleEvent(message("ev3", "audio", "{\\\"file_key\\\":\\\"f1\\\"}"));

        verify(tasks, never()).createFromChat(any(), any(), any(), any(), any());
        verify(notifier).notifyCard(any(FeishuWebhookNotifier.Card.class));
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

    /** 一条 im.message.receive_v1 事件；content 是转义过的 JSON 字符串，与飞书原样一致。 */
    private static String message(String eventId, String type, String content) {
        return "{\"schema\":\"2.0\",\"header\":{\"event_id\":\"" + eventId
                + "\",\"event_type\":\"im.message.receive_v1\"},\"event\":{"
                + "\"sender\":{\"sender_id\":{\"open_id\":\"ou_sender\"}},"
                + "\"message\":{\"chat_id\":\"oc_1\",\"message_type\":\"" + type + "\","
                + "\"mentions\":[{\"key\":\"@_user_1\",\"name\":\"娃弟\"}],"
                + "\"content\":\"" + content + "\"}}}";
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

    private static ProjectEntity project(String id, String name) {
        ProjectEntity project = new ProjectEntity();
        project.setId(id);
        project.setName(name);
        return project;
    }
}
