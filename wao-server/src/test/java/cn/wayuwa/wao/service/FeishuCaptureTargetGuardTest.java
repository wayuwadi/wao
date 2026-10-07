package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.domain.FeishuQuickCollectEventEntity;
import cn.wayuwa.wao.mapper.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FeishuCaptureTargetGuardTest {
    private final DocMapper docs = mock(DocMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final FeishuQuickCollectEventMapper events = mock(FeishuQuickCollectEventMapper.class);
    private final FeishuCaptureTargetGuard guard = new FeishuCaptureTargetGuard(docs, tasks, events);

    @Test
    void taskSourceCanUpdateOnlyBeforeWebContentChanges() {
        var event = event("TASK");
        var task = new TaskEntity(); task.setTitle("来源标题"); task.setDescription("来源正文");
        when(tasks.lockById("target")).thenReturn(task);
        assertThat(guard.mayUpdate(event)).isFalse();
        guard.remember(event);
        var version = ArgumentCaptor.forClass(String.class);
        verify(events).saveTargetVersion(eq("message:om1"), version.capture());
        event.setTargetVersion(version.getValue());
        task.setStatus("进行中");
        assertThat(guard.mayUpdate(event)).isTrue();
        task.setDescription("网页中修改过的正文");
        assertThat(guard.mayUpdate(event)).isFalse();
    }

    @Test
    void noteSourceCannotOverwriteNewerWebRevisionOrDeletedNote() {
        var event = event("NOTE"); event.setTargetVersion("note:3");
        var doc = new DocEntity(); doc.setRev(3);
        when(docs.selectByIdForUpdate("target")).thenReturn(doc);
        assertThat(guard.mayUpdate(event)).isTrue();
        doc.setRev(4);
        assertThat(guard.mayUpdate(event)).isFalse();
        doc.setRev(3); doc.setDeletedAt(java.time.LocalDateTime.now());
        assertThat(guard.mayUpdate(event)).isFalse();
    }
    private FeishuQuickCollectEventEntity event(String kind) {
        var event = new FeishuQuickCollectEventEntity();
        event.setEventKey("message:om1"); event.setTargetId("target"); event.setCaptureKind(kind); return event;
    }
}
