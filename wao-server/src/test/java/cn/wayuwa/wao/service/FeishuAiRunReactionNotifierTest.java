package cn.wayuwa.wao.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import cn.wayuwa.wao.domain.FeishuQuickCollectEventEntity;
import cn.wayuwa.wao.feishu.FeishuApiException;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.mapper.FeishuQuickCollectEventMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class FeishuAiRunReactionNotifierTest {

    private final FeishuQuickCollectEventMapper events = mock(FeishuQuickCollectEventMapper.class);
    private final SettingService settings = mock(SettingService.class);
    private final FeishuOpenApiClient feishu = mock(FeishuOpenApiClient.class);
    private final FeishuQuickCollectReactionPolicy reactionPolicy = new FeishuQuickCollectReactionPolicy(settings);
    private final FeishuAiRunReactionNotifier notifier = new FeishuAiRunReactionNotifier(
            events, settings, feishu, reactionPolicy);

    @Test
    void enqueuedRunAddsFlightReaction() {
        enabled();
        when(events.selectList(any())).thenReturn(List.of(event("t1", "om_source")));

        notifier.reactAfterEnqueued(List.of("t1"));

        verify(feishu).addMessageReaction("token", "om_source", "StatusInFlight");
    }

    @Test
    void succeededRunAddsDoneToItsOriginalMessage() {
        enabled();
        when(events.selectList(any())).thenReturn(List.of(event("t1", "om_source")));

        notifier.reactAfterSucceeded(List.of("t1"));

        verify(feishu).addMessageReaction("token", "om_source", "DONE");
    }

    @Test
    void ordinaryTaskWithoutCapturedMessageDoesNotAddReaction() {
        enabled();
        when(events.selectList(any())).thenReturn(List.of());

        notifier.reactAfterEnqueued(List.of("t1"));

        verify(feishu, never()).addMessageReaction(any(), any(), any());
    }

    @Test
    void completionReactionFailureDoesNotFailAiRun() {
        enabled();
        when(events.selectList(any())).thenReturn(List.of(event("t1", "om_source")));
        doThrow(new FeishuApiException("飞书暂不可用"))
                .when(feishu).addMessageReaction("token", "om_source", "DONE");

        notifier.reactAfterSucceeded(List.of("t1"));

        verify(feishu).addMessageReaction("token", "om_source", "DONE");
    }

    @Test
    void enqueuedReactionWaitsUntilCommit() {
        enabled();
        when(events.selectList(any())).thenReturn(List.of(event("t1", "om_source")));
        TransactionSynchronizationManager.initSynchronization();
        try {
            notifier.reactAfterEnqueued(List.of("t1"));

            verify(feishu, never()).addMessageReaction(any(), any(), any());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(feishu).addMessageReaction("token", "om_source", "StatusInFlight");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void succeededReactionAlsoWaitsUntilCommit() {
        enabled();
        when(events.selectList(any())).thenReturn(List.of(event("t1", "om_source")));
        TransactionSynchronizationManager.initSynchronization();
        try {
            notifier.reactAfterSucceeded(List.of("t1"));

            verify(feishu, never()).addMessageReaction(any(), any(), any());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(feishu).addMessageReaction("token", "om_source", "DONE");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void enabled() {
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("cli_chat");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("secret");
        when(feishu.tenantAccessToken("cli_chat", "secret")).thenReturn("token");
    }

    private static FeishuQuickCollectEventEntity event(String taskId, String messageId) {
        FeishuQuickCollectEventEntity event = new FeishuQuickCollectEventEntity();
        event.setTargetId(taskId);
        event.setMessageId(messageId);
        return event;
    }
}
