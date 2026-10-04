package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.wayuwa.wao.domain.AiAssistantRunEntity;
import cn.wayuwa.wao.dto.ImageItemDto;
import cn.wayuwa.wao.mapper.AiAssistantRunMapper;
import cn.wayuwa.wao.mapper.NoteAiWorkflowMapper;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

class LocalNoteAiServiceTest {
    @TempDir Path directory;
    final AiAssistantRunMapper runs = mock(AiAssistantRunMapper.class);
    final ImageService images = mock(ImageService.class);
    final MockEnvironment environment = new MockEnvironment();
    @SuppressWarnings("unchecked")
    final LocalNoteAiService service = spy(new LocalNoteAiService(runs, images,
            mock(NoteAiWorkflowMapper.class), mock(ObjectProvider.class), environment));

    AiAssistantRunEntity run() {
        ReflectionTestUtils.setField(service, "capability", "local-notes-test");
        var run = new AiAssistantRunEntity();
        run.setId("ac-test"); run.setAiCapabilityId("local-notes-test");
        run.setRequestedModel("gpt-6-astra"); run.setPromptText("合成验收正文");
        run.setUsagePurpose("POLISH");
        return run;
    }

    void claim() { when(runs.claimQueued(eq("ac-test"), isNull(), anyString(), eq("secure_queued"), eq("provider_running"))).thenReturn(1); }

    @Test void checksImageOwnershipAgainBeforeReadingBytesOrStartingCli() throws Exception {
        var references = mock(ReferenceImageService.class);
        ReflectionTestUtils.setField(service, "referenceImages", references);
        var run = run(); run.setMemberId("member"); run.setReferenceImageIds("foreign-image");
        doThrow(new cn.wayuwa.wao.common.BadRequestException("图片不属于当前成员"))
                .when(references).requireOwned(eq("member"), any());
        assertThatThrownBy(() -> service.execute(run, "gpt-6-astra", Duration.ofSeconds(300)))
                .hasMessageContaining("不属于当前成员");
        verify(references, never()).raw(anyString(), anyString());
        run.setReferenceImageIds("duplicate,duplicate");
        assertThatThrownBy(() -> service.execute(run, "gpt-6-astra", Duration.ofSeconds(300)))
                .hasMessageContaining("数量无效");
    }

    @Test void defaultDisabledAndNeverFallsBackToPaidApi() {
        assertThat(service.enabled()).isFalse();
        assertThatThrownBy(() -> service.enqueue("member", "正文", "POLISH", "gpt-6-astra"))
                .hasMessageContaining("尚未启用");
        verifyNoInteractions(runs, images);
    }

    @Test void explicitTianrenEffortNeverReadsNoteSettings() {
        ReflectionTestUtils.setField(service, "ready", true);
        ReflectionTestUtils.setField(service, "capability", "local-notes-test");
        var notes=mock(NoteAiSettingsService.class);ReflectionTestUtils.setField(service,"noteSettings",notes);
        var models=mock(LocalCodexModelService.class);ReflectionTestUtils.setField(service,"localModels",models);
        when(models.validate("gpt-6.1-sol")).thenReturn("gpt-6.1-sol");
        when(models.validateEffort("gpt-6.1-sol","high")).thenReturn("high");
        service.enqueueWithEffort("member","合成卦象","TIANREN_IMAGE","gpt-6.1-sol","high");
        var queued=org.mockito.ArgumentCaptor.forClass(AiAssistantRunEntity.class);verify(runs).insertReadOnlyQueued(queued.capture());
        assertThat(queued.getValue().getReasoningEffort()).isEqualTo("high");assertThat(queued.getValue().getRequestedModel()).isEqualTo("gpt-6.1-sol");verifyNoInteractions(notes);
    }
    @Test void snapshotsConfiguredEffortWhenQueuingNote() {
        ReflectionTestUtils.setField(service, "ready", true);
        ReflectionTestUtils.setField(service, "capability", "local-notes-test");
        var preferences = mock(MemberPreferenceMapper.class);
        when(preferences.find("member", NoteAiSettingsService.DEFAULTS)).thenReturn("{\"reasoningEffort\":\"high\"}");
        ReflectionTestUtils.setField(service, "noteSettings", new NoteAiSettingsService(
                mock(org.springframework.jdbc.core.JdbcTemplate.class), preferences, new ObjectMapper()));
        var models = mock(LocalCodexModelService.class);
        when(models.validateEffort("gpt-6-astra", "high")).thenReturn("high");
        ReflectionTestUtils.setField(service, "localModels", models);
        service.enqueue("member", "合成正文", "POLISH", "gpt-6-astra");
        var queued = org.mockito.ArgumentCaptor.forClass(AiAssistantRunEntity.class);
        verify(runs).insertReadOnlyQueued(queued.capture());
        assertThat(queued.getValue().getReasoningEffort()).isEqualTo("high");
        verify(models).validateEffort("gpt-6-astra", "high");
    }

    @Test void failingWorkflowAndFailureRecordingDoNotPreventAnotherDocumentFromAdvancing() {
        var workflow = mock(NoteAiWorkflowService.class);
        @SuppressWarnings("unchecked")
        var provider = (ObjectProvider<NoteAiWorkflowService>) mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(workflow);
        var failure = new IllegalStateException("阶段失败");
        doThrow(failure).when(workflow).advance("first");
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("暂时不可写"))
                .when(workflow).failAdvance("first", failure);
        environment.withProperty("wao.ai.local-notes.document-ids", "first,second");
        var worker = new LocalNoteAiService(runs, images, mock(NoteAiWorkflowMapper.class), provider, environment);

        ReflectionTestUtils.invokeMethod(worker, "advanceWorkflows");

        verify(workflow).failAdvance("first", failure);
        verify(workflow).advance("second");
        verify(workflow, never()).failAdvance(eq("second"), any());
    }

    @Test void foreignAndDuplicateClaimsNeverInvokeCodex() throws Exception {
        var run = run(); run.setAiCapabilityId("local-codex");
        assertThat(service.process(run)).isFalse();
        verifyNoInteractions(runs);
        run.setAiCapabilityId("local-notes-test");
        assertThat(service.process(run)).isFalse();
        verify(service, never()).execute(any(), anyString(), any());
    }

    @Test void successUsesAstraAndCurrentClaim() throws Exception {
        var run = run(); claim();
        doReturn(new LocalNoteCodexProcess.Result("保留事实的合成结果", null)).when(service).execute(eq(run), eq("gpt-6-astra"), eq(Duration.ofSeconds(300)));
        when(runs.completeProviderSucceeded(anyString(), anyString(), anyString())).thenReturn(1);
        assertThat(service.process(run)).isTrue();
        verify(runs).completeProviderSucceeded(eq("ac-test"), anyString(), eq("保留事实的合成结果"));
        verifyNoInteractions(images);
    }

    @Test void timeoutHasActionableRetryWithoutAnotherProvider() throws Exception {
        var run = run(); claim();
        doThrow(new IllegalStateException("本机 AI 执行超时；原文已保留，请稍后重试")).when(service).execute(any(), anyString(), any());
        service.process(run);
        verify(runs).completeProviderFailed(eq("ac-test"), anyString(), contains("重试"));
        verify(runs, never()).completeProviderSucceeded(anyString(), anyString(), anyString());
    }

    @Test void conversationJobsUseAtomicCompletion() throws Exception {
        var run = run(); run.setConversationId("cv-test"); run.setUsagePurpose("TIANREN"); claim();
        var completion = mock(LocalCodexConversationCompletion.class);
        ReflectionTestUtils.setField(service, "conversationCompletion", completion);
        doReturn(new LocalNoteCodexProcess.Result("合成卦象解读", null)).when(service).execute(any(), anyString(), any());
        service.process(run);
        verify(completion).complete(eq(run), anyString(), eq("合成卦象解读"));
        verify(runs, never()).completeProviderSucceeded(anyString(), anyString(), anyString());
    }

    @Test void cancelledImageClaimDeletesOnlyNewOrphan() throws Exception {
        var run = run(); claim();
        doReturn(new LocalNoteCodexProcess.Result(null, new byte[]{1})).when(service).execute(any(), anyString(), any());
        when(images.store(any(), anyString(), anyString(), anyString())).thenReturn(image());
        service.process(run);
        verify(images).delete("new-image");
    }

    @Test void unknownDatabaseCommitMustNotDeletePossiblyReferencedImage() throws Exception {
        var run = run(); claim();
        doReturn(new LocalNoteCodexProcess.Result(null, new byte[]{1})).when(service).execute(any(), anyString(), any());
        when(images.store(any(), anyString(), anyString(), anyString())).thenReturn(image());
        when(runs.completeProviderSucceeded(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("connection lost"));
        service.process(run);
        verify(images, never()).delete(anyString());
    }

    @Test void stableIdentitySurvivesRestartAndSeparatesInstances() throws Exception {
        String first = LocalNoteAiService.instanceCapability(directory);
        assertThat(LocalNoteAiService.instanceCapability(directory)).isEqualTo(first).hasSize(32);
        assertThat(LocalNoteAiService.instanceCapability(Files.createDirectory(directory.resolve("another")))).isNotEqualTo(first);
    }

    ImageItemDto image() { return new ImageItemDto("new-image", "合成.png", "1 KB", "今天", "合成", "/api/images/new-image/raw", "#fff", "#eee"); }
}
