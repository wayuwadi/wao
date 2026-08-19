package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.dto.DocBlockDto;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.ImageItemDto;
import cn.wayuwa.wao.domain.KbFolderEntity;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FeishuChatSyncServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 7, 25);

    private final SettingService settings = mock(SettingService.class);
    private final FeishuOpenApiClient feishu = mock(FeishuOpenApiClient.class);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final ImageService images = mock(ImageService.class);
    private final FeishuWebhookNotifier notifier = mock(FeishuWebhookNotifier.class);
    private final FeishuChatArchiveVersionService archiveVersions = mock(FeishuChatArchiveVersionService.class);
    private final FeishuChatSyncService service =
            new FeishuChatSyncService(settings, feishu, knowledge, images, notifier,
                    archiveVersions, new ObjectMapper());

    private void configured() {
        when(settings.getAll(any())).thenReturn(Map.of(
                SettingService.CHAT_SYNC_APP_ID, "cli_demo",
                SettingService.CHAT_SYNC_APP_SECRET, "secret",
                SettingService.CHAT_SYNC_CHAT_ID, "oc_chat",
                SettingService.CHAT_SYNC_CHAT_NAME, "研发群",
                SettingService.CHAT_SYNC_KB_ID, "kb1",
                SettingService.CHAT_SYNC_SENDERS,
                "[{\"id\":\"ou_ge\",\"name\":\"娃哥\"},{\"id\":\"ou_di\",\"name\":\"娃弟\"}]"));
        KbFolderEntity folder = new KbFolderEntity();
        folder.setId("f202607");
        when(knowledge.ensureAutoFolder("kb1", "2026 年 7 月")).thenReturn(folder);
        when(feishu.tenantAccessToken("cli_demo", "secret")).thenReturn("t-token");
        when(archiveVersions.nextVersion()).thenReturn(42L);
        when(knowledge.upsertAutoDoc(anyString(), anyString(), anyString(), any(),
                anyString(), any(LocalDate.class), anyLong()))
                .thenReturn(new DocDto("d1", "飞书群聊 · 2026-07-25", "auto", null, null, false,
                        List.of(), List.of(), List.of(), "kb1", "f202607", 1));
    }

    private FeishuMessage text(String sender, int hour, int minute, String body) {
        long millis = DAY.atTime(LocalTime.of(hour, minute)).atZone(ZONE).toInstant().toEpochMilli();
        return new FeishuMessage("om_" + hour + minute, sender, "text",
                "{\"text\":\"" + body + "\"}", millis, false);
    }

    @Test
    void syncWritesOneDocPerDayWithSenderNamesResolved() {
        configured();
        when(feishu.listMessages(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of(
                text("ou_ge", 9, 15, "早上好"),
                text("ou_di", 9, 20, "收到"),
                text("ou_robot_notify_2026", 22, 5, "构建成功")));

        String result = service.syncDay(DAY);

        ArgumentCaptor<List<DocBlockDto>> blocks = ArgumentCaptor.captor();
        verify(knowledge).upsertAutoDoc(eq("kb1"), eq("f202607"), eq("飞书群聊 · 2026-07-25"),
                blocks.capture(), eq("oc_chat"), eq(DAY), eq(42L));
        String rendered = blocks.getValue().stream()
                .map(b -> String.valueOf(b.call()) + b.h() + b.p()).reduce("", String::concat);
        assertThat(rendered).contains("09:15 娃哥", "09:20 娃弟", "早上好");
        // 没配映射的发言人退回可辨识的短 ID，方便回配置页补上
        assertThat(rendered).contains("**22:05 成员 ");
        assertThat(result).contains("2026-07-25").contains("3 条消息");
    }

    @Test
    void syncPullsExactlyTheRequestedDayWindow() {
        configured();
        when(feishu.listMessages(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of());

        service.syncDay(DAY);

        ArgumentCaptor<Long> start = ArgumentCaptor.captor();
        ArgumentCaptor<Long> end = ArgumentCaptor.captor();
        verify(feishu).listMessages(eq("t-token"), eq("oc_chat"), start.capture(), end.capture());
        assertThat(start.getValue()).isEqualTo(DAY.atStartOfDay(ZONE).toEpochSecond());
        assertThat(end.getValue()).isEqualTo(DAY.atTime(LocalTime.of(23, 59, 59)).atZone(ZONE).toEpochSecond());
    }

    @Test
    void silentSyncReturnsStructuredArchiveResultWithoutSendingNotification() {
        configured();
        FeishuMessage message = text("ou_ge", 9, 15, "早上好");
        when(feishu.listMessages(anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(List.of(message));

        FeishuChatSyncService.ChatSyncDayResult result = service.syncDaySilently(DAY);

        assertThat(result.day()).isEqualTo(DAY);
        assertThat(result.chatId()).isEqualTo("oc_chat");
        assertThat(result.kbId()).isEqualTo("kb1");
        assertThat(result.document().id()).isEqualTo("d1");
        assertThat(result.messages()).containsExactly(message);
        verifyNoInteractions(notifier);
    }

    @Test
    void reviewSyncRejectsAChangedChatOrKnowledgeBaseBeforeCallingFeishu() {
        configured();

        assertThatThrownBy(() -> service.syncDaySilently(DAY, "oc_other", "kb1"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("群聊或知识库已变更");

        verifyNoInteractions(feishu);
        verifyNoInteractions(archiveVersions);
    }

    @Test
    void longMediaRenderingRenewsTheOwningReviewLease() {
        configured();
        AtomicInteger heartbeats = new AtomicInteger();
        long millis = DAY.atTime(LocalTime.of(10, 0)).atZone(ZONE).toInstant().toEpochMilli();
        when(feishu.listMessages(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of(
                new FeishuMessage("om_img", "ou_ge", "image", "{\"image_key\":\"img_key\"}", millis, false)));
        when(feishu.downloadImage("t-token", "om_img", "img_key")).thenReturn(new byte[]{1});
        when(images.store(any(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new ImageItemDto("img9", "img_key.png", "1 B", "", "飞书群聊",
                        "/api/images/img9/raw", "", ""));

        service.syncDaySilently(DAY, "oc_chat", "kb1", heartbeats::incrementAndGet);

        assertThat(heartbeats).hasValueGreaterThanOrEqualTo(4);
    }

    @Test
    void reviewEvidenceUsesPlaceholdersInsteadOfClaimingMediaRecognition() {
        long millis = DAY.atStartOfDay(ZONE).toInstant().toEpochMilli();

        assertThat(service.reviewTextOf(new FeishuMessage(
                "image", "ou_ge", "image", "{\"image_key\":\"img1\"}", millis, false)))
                .isEqualTo("[图片]");
        assertThat(service.reviewTextOf(new FeishuMessage(
                "audio", "ou_ge", "audio", "{}", millis, false)))
                .isEqualTo("[语音]");
        assertThat(service.reviewTextOf(new FeishuMessage(
                "video", "ou_ge", "media", "{\"file_name\":\"demo.mp4\"}", millis, false)))
                .isEqualTo("[视频] demo.mp4");
        assertThat(service.reviewTextOf(new FeishuMessage(
                "file", "ou_ge", "file", "{\"file_name\":\"plan.pdf\"}", millis, false)))
                .isEqualTo("[文件] plan.pdf");
    }

    @Test
    void scheduledRunUsesTheCronPlanDateInsteadOfWallClockDate() {
        configured();
        when(feishu.listMessages(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of());

        service.execute(new cn.wayuwa.wao.domain.AutomationJobEntity(),
                LocalDateTime.of(2026, 7, 25, 23, 59));

        verify(knowledge).upsertAutoDoc(eq("kb1"), eq("f202607"), eq("飞书群聊 · 2026-07-25"),
                any(), eq("oc_chat"), eq(DAY), anyLong());
    }

    @Test
    void syncDaysWritesEveryRequestedDayAndReturnsAggregate() {
        configured();
        when(feishu.listMessages(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of());

        String result = service.syncDays(LocalDate.of(2026, 7, 4), LocalDate.of(2026, 7, 6));

        verify(knowledge).upsertAutoDoc(eq("kb1"), eq("f202607"), eq("飞书群聊 · 2026-07-04"),
                any(), eq("oc_chat"), eq(LocalDate.of(2026, 7, 4)), anyLong());
        verify(knowledge).upsertAutoDoc(eq("kb1"), eq("f202607"), eq("飞书群聊 · 2026-07-05"),
                any(), eq("oc_chat"), eq(LocalDate.of(2026, 7, 5)), anyLong());
        verify(knowledge).upsertAutoDoc(eq("kb1"), eq("f202607"), eq("飞书群聊 · 2026-07-06"),
                any(), eq("oc_chat"), eq(LocalDate.of(2026, 7, 6)), anyLong());
        assertThat(result).contains("2026-07-04 至 2026-07-06", "共 3 天、0 条消息");
    }

    @Test
    void syncDaysRejectsAnInvertedRange() {
        assertThatThrownBy(() -> service.syncDays(DAY, DAY.minusDays(1)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("起始日期不能晚于结束日期");
    }

    @Test
    void chatImagesAreRehostedOnLocalImageStore() {
        configured();
        long millis = DAY.atTime(LocalTime.of(10, 0)).atZone(ZONE).toInstant().toEpochMilli();
        when(feishu.listMessages(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of(
                new FeishuMessage("om_img", "ou_ge", "image", "{\"image_key\":\"img_key\"}", millis, false)));
        when(feishu.downloadImage("t-token", "om_img", "img_key")).thenReturn(new byte[]{1, 2, 3});
        when(images.store(any(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new ImageItemDto("img9", "img_key.png", "3 B", "", "飞书群聊", "/api/images/img9/raw", "", ""));

        service.syncDay(DAY);

        ArgumentCaptor<List<DocBlockDto>> blocks = ArgumentCaptor.captor();
        verify(knowledge).upsertAutoDoc(anyString(), anyString(), anyString(), blocks.capture(),
                anyString(), any(LocalDate.class), anyLong());
        assertThat(blocks.getValue().stream().map(DocBlockDto::p).filter(java.util.Objects::nonNull).toList())
                .anyMatch(p -> p.contains("![群聊图片](/api/images/img9/raw)"));
    }

    @Test
    void chatImagesAreStoredWithDeduplicationSoRerunsDoNotPileUpCopies() {
        configured();
        long millis = DAY.atTime(LocalTime.of(10, 0)).atZone(ZONE).toInstant().toEpochMilli();
        when(feishu.listMessages(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of(
                new FeishuMessage("om_img", "ou_ge", "image", "{\"image_key\":\"img_key\"}", millis, false)));
        when(feishu.downloadImage("t-token", "om_img", "img_key")).thenReturn(new byte[]{1, 2, 3});
        when(images.store(any(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new ImageItemDto("img9", "img_key.png", "3 B", "", "飞书群聊", "/api/images/img9/raw", "", ""));

        service.syncDay(DAY);

        verify(images).store(any(), anyString(), anyString(), anyString(), eq(true));
    }

    @Test
    void imageDownloadFailureSkipsThatImageInsteadOfFailingTheRun() {
        configured();
        long millis = DAY.atTime(LocalTime.of(10, 0)).atZone(ZONE).toInstant().toEpochMilli();
        when(feishu.listMessages(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of(
                new FeishuMessage("om_img", "ou_ge", "image", "{\"image_key\":\"img_key\"}", millis, false)));
        when(feishu.downloadImage(anyString(), anyString(), anyString())).thenReturn(null);

        assertThat(service.syncDay(DAY)).startsWith("成功");
    }

    @Test
    void interactiveBotCardIsRenderedAsReadableText() {
        configured();
        long millis = DAY.atTime(LocalTime.of(11, 0)).atZone(ZONE).toInstant().toEpochMilli();
        when(feishu.listMessages(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of(
                new FeishuMessage("om_card", "ou_robot", "interactive",
                        "{\"header\":{\"title\":{\"content\":\"部署成功\"}},\"elements\":[{\"tag\":\"div\",\"text\":{\"content\":\"**改动**\\n已完成同步\"}}]}",
                        millis, false)));

        service.syncDay(DAY);

        ArgumentCaptor<List<DocBlockDto>> blocks = ArgumentCaptor.captor();
        verify(knowledge).upsertAutoDoc(anyString(), anyString(), anyString(), blocks.capture(),
                anyString(), any(LocalDate.class), anyLong());
        String rendered = blocks.getValue().stream().map(DocBlockDto::p)
                .filter(java.util.Objects::nonNull).reduce("", String::concat);
        assertThat(rendered).contains("部署成功", "改动 已完成同步").doesNotContain("[interactive]");
    }

    @Test
    void syncFailsFastWhenChatNotConfigured() {
        when(settings.getAll(any())).thenReturn(Map.of());

        assertThatThrownBy(() -> service.syncDay(DAY))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("群聊");
    }

    @Test
    void configMasksAppSecret() {
        when(settings.getAll(any())).thenReturn(Map.of(
                SettingService.CHAT_SYNC_APP_SECRET, "fake0000000000000000000000secret"));

        assertThat(service.config().appSecretMask()).isEqualTo("fake••••••cret");
        assertThat(service.config().appSecretSet()).isTrue();
    }
}
