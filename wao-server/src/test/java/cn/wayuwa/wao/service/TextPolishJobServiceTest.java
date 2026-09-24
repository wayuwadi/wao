package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.wayuwa.wao.mapper.TextPolishJobMapper;
import cn.wayuwa.wao.mapper.TextPolishJobMapper.Job;
import org.junit.jupiter.api.Test;

class TextPolishJobServiceTest {
    private final TextPolishJobMapper jobs = mock(TextPolishJobMapper.class);
    private final TextPolishService polish = mock(TextPolishService.class);
    private final AiAssistantService assistant = mock(AiAssistantService.class);
    private final TextPolishJobService service = new TextPolishJobService(jobs, polish, assistant);
    private final String request = "0123456789abcdef0123456789abcdef";
    private final String id = "pj" + request;

    @Test void partialOutputIsPreviewOnlyAndNeverEntersHistory() {
        when(jobs.findOwned(id, "m1")).thenReturn(job("WORKSPACE"));
        when(assistant.readOnlyJobResult(id, "ac1"))
                .thenReturn(new AiAssistantService.ReadOnlyJobResult("ac1", "provider_running", "一部分", null))
                .thenReturn(new AiAssistantService.ReadOnlyJobResult("ac1", "failed", "一部分", "断开"));
        var running = service.get("m1", id);
        assertThat(running.preview()).isEqualTo("一部分");
        assertThat(running.polished()).isNull();
        var failed = service.get("m1", id);
        assertThat(failed.preview()).isNull();
        assertThat(failed.polished()).isNull();
        verifyNoInteractions(polish);
    }

    @Test void submitsWithoutWaitingAndPreservesDocumentInstructions() {
        when(jobs.lockMember("m1")).thenReturn("m1");
        when(polish.prepare("m1", "原文")).thenReturn(new TextPolishService.PreparedPolish("原文", "用户方法"));
        when(assistant.enqueueTextPolishJob(eq(id), eq("原文"), contains("Markdown"))).thenReturn("ac1");
        when(assistant.readOnlyJobResult(id, "ac1")).thenReturn(new AiAssistantService.ReadOnlyJobResult("ac1", "secure_queued", null, null));
        var result = service.submit("m1", request, "DOCUMENT", " 原文 ");
        assertThat(result.status()).isEqualTo("QUEUED");
        assertThat(result.polished()).isNull();
        verify(jobs).insert(any());
        verify(polish, never()).recordResult(any(), any(), any(), any(), any());
    }

    @Test void duplicateSubmissionReusesRunAndRejectsChangedInput() {
        when(jobs.lockMember("m1")).thenReturn("m1");
        when(jobs.findOwned(id, "m1")).thenReturn(job("DOCUMENT"));
        when(assistant.readOnlyJobResult(id, "ac1")).thenReturn(new AiAssistantService.ReadOnlyJobResult("ac1", "succeeded", "结果", null));
        assertThat(service.submit("m1", request, "DOCUMENT", "原文").polished()).isEqualTo("结果");
        verify(assistant, never()).enqueueTextPolishJob(any(), any(), any());
        assertThatThrownBy(() -> service.submit("m1", request, "DOCUMENT", "修改后的原文"))
                .hasMessageContaining("请求内容已改变");
    }

    @Test void rejectsOtherMembersBeforeReadingModelOutput() {
        assertThatThrownBy(() -> service.get("m2", id)).hasMessageContaining("不存在");
        verifyNoInteractions(assistant);
    }

    @Test void workspaceResultUsesStableHistoryKeyAndFailedRunNeverWritesHistory() {
        when(jobs.findOwned(id, "m1")).thenReturn(job("WORKSPACE"));
        when(assistant.readOnlyJobResult(id, "ac1"))
                .thenReturn(new AiAssistantService.ReadOnlyJobResult("ac1", "failed", null, "登录失效"))
                .thenReturn(new AiAssistantService.ReadOnlyJobResult("ac1", "succeeded", "结果", null));
        assertThat(service.get("m1", id).message()).isEqualTo("登录失效");
        verifyNoInteractions(polish);
        service.get("m1", id);
        verify(polish).recordResult("ac1", "m1", "方法", "原文", "结果");
    }

    private Job job(String kind) { return new Job(id, "m1", kind, "原文", "方法", "ac1", null); }
}
