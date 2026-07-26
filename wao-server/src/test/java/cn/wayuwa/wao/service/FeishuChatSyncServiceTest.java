package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

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
    private final FeishuChatSyncService service =
            new FeishuChatSyncService(settings, feishu, knowledge, images, notifier, new ObjectMapper());

    private void configured() {
        when(settings.get(SettingService.CHAT_SYNC_APP_ID)).thenReturn("cli_demo");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET)).thenReturn("secret");
        when(settings.get(SettingService.CHAT_SYNC_CHAT_ID)).thenReturn("oc_chat");
        when(settings.get(SettingService.CHAT_SYNC_KB_ID)).thenReturn("kb1");
        KbFolderEntity folder = new KbFolderEntity();
        folder.setId("f202607");
        when(knowledge.ensureAutoFolder("kb1", "2026 年 7 月")).thenReturn(folder);
        when(settings.get(SettingService.CHAT_SYNC_SENDERS))
                .thenReturn("[{\"id\":\"ou_ge\",\"name\":\"娃哥\"},{\"id\":\"ou_di\",\"name\":\"娃弟\"}]");
        when(settings.get(eq(SettingService.CHAT_SYNC_CHAT_NAME), anyString())).thenReturn("研发群");
        when(feishu.tenantAccessToken("cli_demo", "secret")).thenReturn("t-token");
        when(knowledge.upsertAutoDoc(anyString(), anyString(), anyString(), any()))
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
        verify(knowledge).upsertAutoDoc(eq("kb1"), eq("f202607"), eq("飞书群聊 · 2026-07-25"), blocks.capture());
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
    void chatImagesAreRehostedOnLocalImageStore() {
        configured();
        long millis = DAY.atTime(LocalTime.of(10, 0)).atZone(ZONE).toInstant().toEpochMilli();
        when(feishu.listMessages(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of(
                new FeishuMessage("om_img", "ou_ge", "image", "{\"image_key\":\"img_key\"}", millis, false)));
        when(feishu.downloadImage("t-token", "om_img", "img_key")).thenReturn(new byte[]{1, 2, 3});
        when(images.store(any(), anyString(), anyString(), anyString()))
                .thenReturn(new ImageItemDto("img9", "img_key.png", "3 B", "", "飞书群聊", "/api/images/img9/raw", "", ""));

        service.syncDay(DAY);

        ArgumentCaptor<List<DocBlockDto>> blocks = ArgumentCaptor.captor();
        verify(knowledge).upsertAutoDoc(anyString(), anyString(), anyString(), blocks.capture());
        assertThat(blocks.getValue().stream().map(DocBlockDto::p).filter(java.util.Objects::nonNull).toList())
                .anyMatch(p -> p.contains("![群聊图片](/api/images/img9/raw)"));
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
        verify(knowledge).upsertAutoDoc(anyString(), anyString(), anyString(), blocks.capture());
        String rendered = blocks.getValue().stream().map(DocBlockDto::p)
                .filter(java.util.Objects::nonNull).reduce("", String::concat);
        assertThat(rendered).contains("部署成功", "改动 已完成同步").doesNotContain("[interactive]");
    }

    @Test
    void syncFailsFastWhenChatNotConfigured() {
        when(settings.get(SettingService.CHAT_SYNC_CHAT_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.syncDay(DAY))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("群聊");
    }

    @Test
    void configMasksAppSecret() {
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET)).thenReturn("fake0000000000000000000000secret");
        when(settings.get(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));

        assertThat(service.config().appSecretMask()).isEqualTo("fake••••••cret");
        assertThat(service.config().appSecretSet()).isTrue();
    }
}
