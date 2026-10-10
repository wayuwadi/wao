package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.wayuwa.wao.mapper.TextPolishJobMapper;
import cn.wayuwa.wao.mapper.TextPolishJobMapper.Job;
import org.junit.jupiter.api.Test;
import java.util.List;

class TextPolishJobServiceTest {
    private final TextPolishJobMapper jobs = mock(TextPolishJobMapper.class);
    private final TextPolishService polish = mock(TextPolishService.class);
    private final AiAssistantService assistant = mock(AiAssistantService.class);
    private final NoteTagService noteTags = mock(NoteTagService.class);
    private final TextPolishJobService service = new TextPolishJobService(jobs, polish, assistant, noteTags);
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

    @Test void failedNoteBodyKeepsOriginalForRetryAndNeverReturnsPartialOutput() {
        Job saved = job("NOTE_BODY");
        when(jobs.findOwned(id, "m1")).thenReturn(saved);
        for (String terminal : List.of("failed", "cancelled")) {
            when(assistant.readOnlyJobResult(id, "ac1"))
                    .thenReturn(new AiAssistantService.ReadOnlyJobResult("ac1", terminal, "不可发布的部分结果", null));
            var result = service.get("m1", id);
            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.message()).contains("重试", "原文已保留");
            assertThat(result.polished()).isNull();
            assertThat(result.preview()).isNull();
            assertThat(saved.originalText()).isEqualTo("原文");
        }
        verify(jobs, never()).insert(any());
        verifyNoInteractions(polish);
    }

    @Test void submitsWithoutWaitingAndPreservesDocumentInstructions() {
        when(jobs.lockMember("m1")).thenReturn("m1");
        when(polish.prepare("m1", "原文")).thenReturn(new TextPolishService.PreparedPolish("原文", "用户方法"));
        when(assistant.enqueueDocumentPolishJob(eq(id), eq("原文"), contains("Markdown"))).thenReturn("ac1");
        when(assistant.readOnlyJobResult(id, "ac1")).thenReturn(new AiAssistantService.ReadOnlyJobResult("ac1", "secure_queued", null, null));
        var result = service.submit("m1", request, "DOCUMENT", " 原文 ");
        assertThat(result.status()).isEqualTo("QUEUED");
        assertThat(result.polished()).isNull();
        verify(jobs).insert(any());
        verify(assistant).enqueueDocumentPolishJob(eq(id),eq("原文"),contains("Markdown"));
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

    @Test void noteTitleUsesReadOnlyJobAndReturnsOneCleanLineWithoutPolishHistory() {
        when(jobs.lockMember("m1")).thenReturn("m1");
        when(assistant.enqueueNoteTitleJob(eq(id), eq("原文"), eq("从体验角度概括"), eq("gpt-6-sol"))).thenReturn("ac1");
        when(assistant.readOnlyJobResult(id, "ac1"))
                .thenReturn(new AiAssistantService.ReadOnlyJobResult("ac1", "succeeded", "# “关于小记的想法”\n多余解释", null));
        var result = service.submit("m1", request, "NOTE_TITLE", "原文", "gpt-6-sol", "从体验角度概括", null);
        assertThat(result.polished()).isEqualTo("关于小记的想法");
        verify(assistant).enqueueNoteTitleJob(eq(id), eq("原文"), eq("从体验角度概括"), eq("gpt-6-sol"));
        verify(jobs).insert(argThat(job -> job.originalText().startsWith("sha256:") && !job.originalText().contains("原文")));
        verifyNoInteractions(polish);
        verify(polish, never()).recordResult(any(), any(), any(), any(), any());
    }

    @Test void noteTagsOnlyReturnsUpToThreeExactExistingTags() {
        when(jobs.lockMember("m1")).thenReturn("m1");
        when(noteTags.list()).thenReturn(List.of("创作", "计划", "复盘", "灵感"));
        when(assistant.enqueueNoteTagsJob(eq(id), eq("一段正文"), anyString(), eq("gpt-6-luna"))).thenReturn("ac1");
        when(assistant.readOnlyJobResult(id, "ac1")).thenReturn(new AiAssistantService.ReadOnlyJobResult(
                "ac1", "succeeded", "计划\n新建标签\n# 创作\n复盘\n灵感", null));
        var result = service.submit("m1", request, "NOTE_TAGS", "一段正文", "gpt-6-luna", null, null,
                List.of("创作", "计划", "复盘", "灵感"));
        assertThat(result.polished()).isEqualTo("计划\n创作\n复盘");
        verify(assistant).enqueueNoteTagsJob(eq(id), eq("一段正文"), contains("灵感"), eq("gpt-6-luna"));
        verify(jobs).insert(argThat(job -> job.originalText().startsWith("sha256:") && job.promptSnapshot().contains("allowed_tags_json")));
        verifyNoInteractions(polish);
    }

    @Test void noteTagsRejectEmptyExistingTagList() {
        when(jobs.lockMember("m1")).thenReturn("m1");
        when(noteTags.list()).thenReturn(List.of());
        assertThatThrownBy(() -> service.submit("m1", request, "NOTE_TAGS", "一段正文", "gpt-6-luna", null, null, List.of()))
                .hasMessageContaining("已有标签");
        verify(assistant, never()).enqueueNoteTagsJob(any(), any(), any(), any());
    }
    @Test void structuredTagReasonsCannotCreateUnknownTags() {
        when(jobs.lockMember("m1")).thenReturn("m1");when(noteTags.list()).thenReturn(List.of("创作"));
        when(assistant.enqueueNoteTagsJob(eq(id),eq("一段正文"),anyString(),eq("gpt-6-luna"))).thenReturn("ac1");
        when(assistant.readOnlyJobResult(id,"ac1")).thenReturn(new AiAssistantService.ReadOnlyJobResult("ac1","succeeded","{\"tags\":[{\"name\":\"创作\",\"reason\":\"记录写作\"},{\"name\":\"伪造新标签\",\"reason\":\"不要创建\"}]}",null));
        assertThat(service.submit("m1",request,"NOTE_TAGS","一段正文","gpt-6-luna",null,null,List.of("创作")).polished()).isEqualTo("创作");
    }

    @Test void noteTagsIgnoreClientValuesThatAreNotPersistedTags() {
        when(jobs.lockMember("m1")).thenReturn("m1");
        when(noteTags.list()).thenReturn(List.of("创作"));
        when(assistant.enqueueNoteTagsJob(eq(id), eq("一段正文"), contains("创作"), eq("gpt-6-luna"))).thenReturn("ac1");
        when(assistant.readOnlyJobResult(id, "ac1")).thenReturn(new AiAssistantService.ReadOnlyJobResult("ac1", "secure_queued", null, null));
        service.submit("m1", request, "NOTE_TAGS", "一段正文", "gpt-6-luna", null, null, List.of("创作", "客户端伪造"));
        verify(assistant).enqueueNoteTagsJob(eq(id), eq("一段正文"), eq("[\"创作\"]"), eq("gpt-6-luna"));
    }

    private Job job(String kind) { return new Job(id, "m1", kind, "原文", "方法", "ac1", null); }
}
