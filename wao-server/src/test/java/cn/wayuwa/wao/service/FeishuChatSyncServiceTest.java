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

    @Test
    void retiredEntryPointsNeverReadFeishuOrWriteKnowledge() {
        assertThatThrownBy(() -> service.syncDay(DAY)).hasMessageContaining("已停用");
        assertThatThrownBy(() -> service.syncDaySilently(DAY)).hasMessageContaining("已停用");
        assertThatThrownBy(() -> service.syncDaySilently(DAY, "chat", "kb", () -> {})).hasMessageContaining("已停用");
        assertThatThrownBy(() -> service.syncDays(DAY, DAY.plusDays(1))).hasMessageContaining("已停用");
        assertThatThrownBy(service::execute).hasMessageContaining("已停用");
        assertThatThrownBy(() -> service.execute(null, DAY.atStartOfDay())).hasMessageContaining("已停用");
        assertThatThrownBy(service::requireSyncAvailable).hasMessageContaining("已停用");
        verifyNoInteractions(feishu, knowledge, images, notifier, archiveVersions);
    }

    @Test
    void taskCollectionConfigurationStillMasksSecret() {
        when(settings.getAll(any())).thenReturn(Map.of(
                SettingService.CHAT_SYNC_APP_ID, "app", SettingService.CHAT_SYNC_APP_SECRET, "secret-value",
                SettingService.CHAT_SYNC_CHAT_ID, "chat"));
        assertThat(service.config().appSecretSet()).isTrue();
        assertThat(service.config().appSecretMask()).doesNotContain("secret-value");
        assertThat(service.config().chatId()).isEqualTo("chat");
        verifyNoInteractions(feishu);
    }

    @Test
    void dailyArchiveFetchesExactlyPreviousShanghaiDayAndReusesStableDocumentFence() {
        configuredArchive();
        long noon = DAY.atTime(12, 0).atZone(ZONE).toInstant().toEpochMilli();
        when(feishu.listMessages(eq("token"), eq("chat"), anyLong(), anyLong())).thenReturn(List.of(
                new FeishuMessage("message", "sender", "text", "{\"text\":\"真实当日文字\"}", noon, false),
                new FeishuMessage("gone", "sender", "text", "{\"text\":\"已撤回\"}", noon, true)));
        var result = service.archivePreviousDay(DAY.plusDays(1), "chat", "kb", () -> {});
        assertThat(result.day()).isEqualTo(DAY);
        verify(feishu).listMessages("token", "chat", DAY.atStartOfDay(ZONE).toEpochSecond() - 1,
                DAY.plusDays(1).atStartOfDay(ZONE).toEpochSecond());
        @SuppressWarnings("unchecked") ArgumentCaptor<List<DocBlockDto>> blocks = ArgumentCaptor.forClass(List.class);
        verify(knowledge).upsertAutoDoc(eq("kb"), eq("folder"), eq("飞书群聊 · 2026-07-25"), blocks.capture(),
                eq("chat"), eq(DAY), eq(91L));
        assertThat(blocks.getValue().toString()).contains("12:00 映射发言人", "真实当日文字", "次日上海 00:00")
                .doesNotContain("已撤回");
        verify(feishu, org.mockito.Mockito.never()).listMessageReactions(anyString(), anyString(), anyString());
        verifyNoInteractions(notifier);
    }

    @Test
    void dailyArchiveIncludesFirstAndLastMillisecondsButExcludesAdjacentDays() {
        configuredArchive();
        long start = DAY.atStartOfDay(ZONE).toInstant().toEpochMilli();
        long end = DAY.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        var beforeDay = new FeishuMessage("before-day", "sender", "text",
                "{\"text\":\"前一天最后一毫秒\"}", start - 1, false);
        var midnight = new FeishuMessage("midnight", "sender", "text",
                "{\"text\":\"当日午夜\"}", start, false);
        var lastMillisecond = new FeishuMessage("last-millisecond", "sender", "text",
                "{\"text\":\"当日末秒999毫秒\"}", end - 1, false);
        var nextDay = new FeishuMessage("next-day", "sender", "text",
                "{\"text\":\"次日午夜\"}", end, false);
        when(feishu.listMessages("token", "chat", start / 1000 - 1, end / 1000))
                .thenReturn(List.of(beforeDay, midnight, lastMillisecond, nextDay));

        var result = service.archivePreviousDay(DAY.plusDays(1), "chat", "kb", () -> {});

        assertThat(result.messages()).containsExactly(midnight, lastMillisecond);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<DocBlockDto>> blocks = ArgumentCaptor.forClass(List.class);
        verify(knowledge).upsertAutoDoc(eq("kb"), eq("folder"), anyString(), blocks.capture(),
                eq("chat"), eq(DAY), eq(91L));
        assertThat(blocks.getValue().toString()).contains("00:00 映射发言人", "23:59 映射发言人", "当日午夜", "当日末秒999毫秒")
                .doesNotContain("前一天最后一毫秒", "次日午夜");
        verify(feishu).listMessages("token", "chat", start / 1000 - 1, end / 1000);
        verify(feishu, org.mockito.Mockito.never()).listMessageReactions(anyString(), anyString(), anyString());
    }

    @Test
    void emptyDayStillProducesOneTruthfulArchiveWithoutImageOrReactionReads() {
        configuredArchive();
        when(feishu.listMessages(eq("token"), eq("chat"), anyLong(), anyLong())).thenReturn(List.of());
        service.archivePreviousDay(DAY.plusDays(1), "chat", "kb", () -> {});
        @SuppressWarnings("unchecked") ArgumentCaptor<List<DocBlockDto>> blocks = ArgumentCaptor.forClass(List.class);
        verify(knowledge).upsertAutoDoc(anyString(), anyString(), anyString(), blocks.capture(), eq("chat"), eq(DAY), eq(91L));
        assertThat(blocks.getValue().toString()).contains("这一天群里没有消息");
        verifyNoInteractions(images, notifier);
        verify(feishu, org.mockito.Mockito.never()).listMessageReactions(anyString(), anyString(), anyString());
    }

    @Test
    void changedTargetBeforeFetchOrAfterFetchCannotWriteArchive() {
        configuredArchive();
        assertThatThrownBy(() -> service.archivePreviousDay(DAY.plusDays(1), "different", "kb", () -> {}))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(feishu, knowledge, archiveVersions);
        when(feishu.listMessages(eq("token"), eq("chat"), anyLong(), anyLong())).thenAnswer(call -> {
            when(settings.getAll(any())).thenReturn(Map.of(SettingService.CHAT_SYNC_CHAT_ID, "changed"));
            return List.of();
        });
        assertThatThrownBy(() -> service.archivePreviousDay(DAY.plusDays(1), "chat", "kb", () -> {}))
                .hasMessageContaining("已变更");
        verifyNoInteractions(knowledge, images, notifier);
    }

    @Test
    void imageArchiveReusesImageStorageWithoutReadingReactions() {
        configuredArchive();
        long noon = DAY.atTime(12, 0).atZone(ZONE).toInstant().toEpochMilli();
        when(feishu.listMessages(eq("token"), eq("chat"), anyLong(), anyLong())).thenReturn(List.of(
                new FeishuMessage("image-message", "sender", "image", "{\"image_key\":\"image-key\"}", noon, false)));
        when(feishu.downloadImage("token", "image-message", "image-key")).thenReturn(new byte[] {1, 2});
        var image = mock(ImageItemDto.class); when(image.url()).thenReturn("/api/images/stored-image");
        when(images.store(any(), anyString(), anyString(), anyString(), anyBoolean())).thenReturn(image);
        service.archivePreviousDay(DAY.plusDays(1), "chat", "kb", () -> {});
        verify(images).store(any(), eq("image/png"), eq("image-key.png"), eq("飞书群聊 · 真实群名"), eq(true));
        verify(feishu, org.mockito.Mockito.never()).listMessageReactions(anyString(), anyString(), anyString());
    }

    private void configuredArchive() {
        when(settings.getAll(any())).thenReturn(Map.of(
                SettingService.CHAT_SYNC_APP_ID, "app", SettingService.CHAT_SYNC_APP_SECRET, "secret-value",
                SettingService.CHAT_SYNC_CHAT_ID, "chat", SettingService.CHAT_SYNC_KB_ID, "kb",
                SettingService.CHAT_SYNC_CHAT_NAME, "真实群名", SettingService.CHAT_SYNC_SENDERS,
                "[{\"id\":\"sender\",\"name\":\"映射发言人\"}]"));
        when(feishu.tenantAccessToken("app", "secret-value")).thenReturn("token");
        when(archiveVersions.nextVersion()).thenReturn(91L);
        var folder = new KbFolderEntity(); folder.setId("folder");
        when(knowledge.ensureAutoFolder("kb", "2026 年 7 月")).thenReturn(folder);
        when(knowledge.upsertAutoDoc(anyString(), anyString(), anyString(), any(), anyString(), any(), anyLong()))
                .thenReturn(mock(DocDto.class));
    }
}
