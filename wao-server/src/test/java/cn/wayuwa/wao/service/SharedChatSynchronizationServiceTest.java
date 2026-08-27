package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.SharedChatSyncRunEntity;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuChatMember;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuMessage;
import cn.wayuwa.wao.mapper.SharedChatSyncRunMapper;
import cn.wayuwa.wao.mapper.SharedChatSyncStateMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SharedChatSynchronizationServiceTest {
    private final SettingService settings = mock(SettingService.class);
    private final FeishuOpenApiClient feishu = mock(FeishuOpenApiClient.class);
    private final SharedChatFeishuBridge bridge = mock(SharedChatFeishuBridge.class);
    private final SharedChatSyncRunMapper runs = mock(SharedChatSyncRunMapper.class);
    private final SharedChatSyncStateMapper state = mock(SharedChatSyncStateMapper.class);
    private final SharedChatSynchronizationService service = new SharedChatSynchronizationService(
            settings, feishu, bridge, runs, state);

    @Test
    void dailyReconciliationPersistsScannedChangesAndAttributableBytes() {
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("app");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("secret");
        when(settings.get(SettingService.SHARED_CHAT_CHAT_ID, "")).thenReturn("oc_real");
        when(settings.get(SettingService.SHARED_CHAT_CHAT_NAME, "飞书群聊")).thenReturn("娃屋日常");
        when(feishu.tenantAccessToken("app", "secret")).thenReturn("token");
        when(state.claim(eq("daily"), any(), any(), any())).thenReturn(1);
        when(feishu.listChatMembers("token", "oc_real"))
                .thenReturn(List.of(new FeishuChatMember("ou1", "娃弟")));
        FeishuMessage first = new FeishuMessage("om1", "ou1", "text", "{\"text\":\"新增\"}", 1L, false);
        FeishuMessage second = new FeishuMessage("om2", "ou1", "text", "{\"text\":\"编辑\"}", 2L, false);
        when(feishu.listMessages(eq("token"), eq("oc_real"), any(Long.class), any(Long.class)))
                .thenReturn(List.of(first, second));
        when(bridge.importMessage(eq("token"), eq("oc_real"), eq("娃屋日常"), eq(first), any()))
                .thenReturn(new SharedChatFeishuBridge.ImportResult(1, 0, 128));
        when(bridge.importMessage(eq("token"), eq("oc_real"), eq("娃屋日常"), eq(second), any()))
                .thenReturn(new SharedChatFeishuBridge.ImportResult(0, 1, 64));

        var result = service.synchronizeDay(LocalDate.of(2026, 8, 26), "NIGHTLY");

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.messagesScanned()).isEqualTo(2);
        assertThat(result.messagesInserted()).isEqualTo(1);
        assertThat(result.messagesUpdated()).isEqualTo(1);
        assertThat(result.contentBytesAdded()).isEqualTo(192);
        ArgumentCaptor<SharedChatSyncRunEntity> saved = ArgumentCaptor.forClass(SharedChatSyncRunEntity.class);
        verify(runs).updateById(saved.capture());
        assertThat(saved.getValue().getFinishedAt()).isNotNull();
    }
}
