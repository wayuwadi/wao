package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;

import cn.wayuwa.wao.domain.FeishuQuickCollectEventEntity;
import cn.wayuwa.wao.mapper.FeishuQuickCollectEventMapper;
import org.junit.jupiter.api.Test;

class FeishuQuickCollectRecallServiceTest {

    private final FeishuQuickCollectEventMapper events = mock(FeishuQuickCollectEventMapper.class);
    private final TaskService tasks = mock(TaskService.class);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final FeishuQuickCollectRecallService service = new FeishuQuickCollectRecallService(events, tasks, knowledge);

    @Test
    void recalledNoteDeletesOnlyItsLinkedNote() {
        FeishuQuickCollectEventEntity event = event("om_note", "oc_chat", "fn123", "CREATED");
        event.setCaptureKind("NOTE");
        when(events.selectById("message:om_note")).thenReturn(event);
        doAnswer(invocation -> { event.setStatus("RECALLING"); return 1; })
                .when(events).markRecallPending(eq("message:om_note"), any());

        assertThat(service.recall("ev-note", "om_note", "oc_chat")).isTrue();

        verify(knowledge).deleteNoteFromChat("fn123");
        verify(tasks, never()).deleteFromChat(any());
    }

    @Test
    void linkedMessageIsMarkedRecalledAndItsTaskIsDeleted() {
        FeishuQuickCollectEventEntity event = event("om_source", "oc_chat", "t9", "CREATED");
        when(events.selectById("message:om_source")).thenReturn(event);
        doAnswer(invocation -> {
            event.setStatus("RECALLING");
            return 1;
        }).when(events).markRecallPending(eq("message:om_source"), any());

        assertThat(service.recall("ev-recall", "om_source", "oc_chat")).isTrue();

        verify(tasks).deleteFromChat("t9");
        verify(events).completeRecall(eq("message:om_source"), any());
    }

    @Test
    void recallBeforeReceiveCreatesATombstoneThatPreventsLateCreation() {
        AtomicReference<FeishuQuickCollectEventEntity> inserted = new AtomicReference<>();
        when(events.insert(any(FeishuQuickCollectEventEntity.class))).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });

        assertThat(service.recall("ev-first", "om_early", "oc_chat")).isTrue();

        assertThat(inserted.get().getEventKey()).isEqualTo("message:om_early");
        assertThat(inserted.get().getStatus()).isEqualTo("RECALLING");
        assertThat(inserted.get().getTargetId()).isNull();
        verify(tasks, never()).deleteFromChat(any());
    }

    @Test
    void sameMessageIdFromAnotherChatCannotDeleteTheTask() {
        when(events.selectById("message:om_source"))
                .thenReturn(event("om_source", "oc_original", "t9", "CREATED"));

        assertThat(service.recall("ev-other", "om_source", "oc_other")).isFalse();

        verify(events, never()).markRecallPending(any(), any());
        verify(tasks, never()).deleteFromChat(any());
    }

    @Test
    void completedRecallDoesNotRepeatTheTaskDeletionOnEveryPoll() {
        when(events.selectById("message:om_done"))
                .thenReturn(event("om_done", "oc_chat", "t9", "RECALLED"));

        assertThat(service.recall("", "om_done", "oc_chat")).isTrue();

        verify(tasks, never()).deleteFromChat(any());
        verify(events, never()).markRecallPending(any(), any());
    }

    private static FeishuQuickCollectEventEntity event(String messageId, String chatId,
            String targetId, String status) {
        FeishuQuickCollectEventEntity event = new FeishuQuickCollectEventEntity();
        event.setEventKey("message:" + messageId);
        event.setMessageId(messageId);
        event.setChatId(chatId);
        event.setTargetId(targetId);
        event.setStatus(status);
        return event;
    }
}
