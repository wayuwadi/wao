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
}
