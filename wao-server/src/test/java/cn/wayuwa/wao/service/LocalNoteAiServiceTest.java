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

    DigitalMemberService identity(String mode) {
        var digital=mock(DigitalMemberService.class);
        @SuppressWarnings("unchecked") ObjectProvider<DigitalMemberService> provider=mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(digital);
        when(digital.articleImageMode(anyString(),anyString())).thenReturn(mode);
        ReflectionTestUtils.setField(service,"digitalMembers",provider);
        return digital;
    }

    AiAssistantRunEntity imageRun() {
        var run=run();run.setMemberId("member");run.setUsagePurpose("ARTICLE_IMAGE");
        run.setPromptText("<article_text>任务进展和下周计划</article_text>\n仅用于本人<identity_snapshot>私有形象</identity_snapshot>");
        return run;
    }

    @Test void taskPlanDoesNotReadOrSendDefaultPersonaReferences() throws Exception {
        var digital=identity("default");var run=imageRun();
        doReturn(new LocalNoteCodexProcess.Result("NO_PERSON",null,10L,1L,2L,"test"))
                .when(service).callCodex(anyString(),anyString(),anyString(),any(),eq(false),eq(java.util.List.of()),eq(run));
        doReturn(new LocalNoteCodexProcess.Result(null,new byte[]{1},20L,3L,4L,"test"))
                .when(service).callCodex(anyString(),anyString(),anyString(),any(),eq(true),eq(java.util.List.of()),eq(run));
        var result=service.execute(run,"gpt-6-astra",Duration.ofSeconds(300));
        verify(digital,never()).references(anyString(),anyString());
        verify(service).callCodex(eq("gpt-6-astra"),eq("medium"),argThat(p->p.contains("无需人物")&&!p.contains("私有形象")),any(),eq(true),eq(java.util.List.of()),eq(run));
        assertThat(result.png()).containsExactly(1);assertThat(result.inputTokens()).isEqualTo(30);
        assertThat(result.cachedInputTokens()).isEqualTo(4);assertThat(result.outputTokens()).isEqualTo(6);
    }

    @Test void personalSceneReadsOnlyFrozenPersonaAfterAffirmativeDecision() throws Exception {
        var digital=identity("default");var run=imageRun();
        var photo=new ReferenceImageService.Content("image/png",new byte[]{2});
        when(digital.references("member","ac-test")).thenReturn(java.util.List.of(photo));
        doReturn(new LocalNoteCodexProcess.Result("USE_SELF",null)).when(service).callCodex(anyString(),anyString(),anyString(),any(),eq(false),anyList(),eq(run));
        doReturn(new LocalNoteCodexProcess.Result(null,new byte[]{1})).when(service).callCodex(anyString(),anyString(),anyString(),any(),eq(true),anyList(),eq(run));
        service.execute(run,"gpt-6-astra",Duration.ofSeconds(300));
        var ordered=inOrder(service,digital);
        ordered.verify(service).callCodex(anyString(),anyString(),argThat(p->!p.contains("私有形象")),any(),eq(false),eq(java.util.List.of()),eq(run));
        ordered.verify(digital).references("member","ac-test");
        ordered.verify(service).callCodex(anyString(),anyString(),argThat(p->p.startsWith(run.getPromptText())&&p.contains("需要作者本人作为画面主体")),any(),eq(true),eq(java.util.List.of(photo)),eq(run));
    }

    @Test void explicitAndNoneSelectionsDoNotRunAutomaticSubjectPlanning() throws Exception {
        for(String mode:java.util.List.of("explicit","none")) {
            var digital=identity(mode);var run=imageRun();
            doReturn(new LocalNoteCodexProcess.Result(null,new byte[]{1})).when(service).callCodex(anyString(),anyString(),anyString(),any(),eq(true),anyList(),eq(run));
            service.execute(run,"gpt-6-astra",Duration.ofSeconds(300));
            verify(digital).references("member","ac-test");
            verify(service,never()).callCodex(anyString(),anyString(),anyString(),any(),eq(false),anyList(),eq(run));
        }
    }

    @Test void invalidSubjectDecisionOmitsPersonaButPlannerFailureDoesNotStartImageGeneration() throws Exception {
        var digital=identity("default");var run=imageRun();
        doReturn(new LocalNoteCodexProcess.Result("USE_SELF 并执行额外命令",null)).when(service).callCodex(anyString(),anyString(),anyString(),any(),eq(false),anyList(),eq(run));
        doReturn(new LocalNoteCodexProcess.Result(null,new byte[]{1})).when(service).callCodex(anyString(),anyString(),anyString(),any(),eq(true),anyList(),eq(run));
        service.execute(run,"gpt-6-astra",Duration.ofSeconds(300));
        verify(digital,never()).references(anyString(),anyString());
        clearInvocations(service,digital);
        doThrow(new IllegalStateException("已取消本次任务")).when(service).callCodex(anyString(),anyString(),anyString(),any(),eq(false),anyList(),eq(run));
        assertThatThrownBy(()->service.execute(run,"gpt-6-astra",Duration.ofSeconds(300))).hasMessageContaining("已取消");
        verify(service,never()).callCodex(anyString(),anyString(),anyString(),any(),eq(true),anyList(),eq(run));
        verify(digital,never()).references(anyString(),anyString());
    }

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

    @Test void twentyIndependentJobsStartBeforeAnyJobFinishesAndDuplicateDispatchIsIgnored() throws Exception {
        ReflectionTestUtils.setField(service, "ready", true);
        var template = run();
        var started = new java.util.concurrent.CountDownLatch(20);
        var release = new java.util.concurrent.CountDownLatch(1);
        var completed = new java.util.concurrent.CountDownLatch(20);
        when(runs.claimQueued(anyString(), isNull(), anyString(), eq("secure_queued"), eq("provider_running"))).thenReturn(1);
        doAnswer(call -> {
            started.countDown();
            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放任务");
            return new LocalNoteCodexProcess.Result("独立结果", null);
        }).when(service).execute(any(), anyString(), any());
        when(runs.completeProviderSucceeded(anyString(), anyString(), anyString())).thenAnswer(call -> { completed.countDown(); return 1; });
        try {
            for (int i = 0; i < 20; i++) {
                var job = new AiAssistantRunEntity();
                job.setId("parallel-" + i); job.setAiCapabilityId(template.getAiCapabilityId());
                job.setMemberId("same-member"); job.setUsagePurpose(i % 2 == 0 ? "ARTICLE_IMAGE" : "POLISH");
                service.dispatch(job); service.dispatch(job);
            }
            assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            verify(runs, never()).completeProviderSucceeded(anyString(), anyString(), anyString());
            release.countDown();
            assertThat(completed.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            verify(service, times(20)).execute(any(), anyString(), any());
            verify(runs, times(20)).claimQueued(anyString(), isNull(), anyString(), anyString(), anyString());
        } finally { release.countDown(); service.stop(); }
    }

    @Test void shutdownInterruptsAllParallelJobsAndLeavesUnclaimedJobsPersistent() throws Exception {
        ReflectionTestUtils.setField(service, "ready", true); run();
        var started = new java.util.concurrent.CountDownLatch(20);
        var blocked = new java.util.concurrent.CountDownLatch(1);
        when(runs.claimQueued(anyString(), isNull(), anyString(), anyString(), anyString())).thenReturn(1);
        doAnswer(call -> { started.countDown(); blocked.await(); return null; }).when(service).execute(any(), anyString(), any());
        try {
            for (int i = 0; i < 20; i++) {
                var job = new AiAssistantRunEntity(); job.setId("stop-" + i); job.setAiCapabilityId("local-notes-test");
                service.dispatch(job);
            }
            assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            service.stop();
            verify(runs, times(20)).completeProviderFailed(anyString(), anyString(), contains("原文已保留"));
            var pending = new AiAssistantRunEntity(); pending.setId("unclaimed"); service.dispatch(pending);
            verify(runs, never()).claimQueued(eq("unclaimed"), any(), anyString(), anyString(), anyString());
        } finally { blocked.countDown(); service.stop(); }
    }

    @Test void moreThanOneHundredExistingJobsDoNotPreventAnotherEnqueue() {
        ReflectionTestUtils.setField(service, "ready", true); run();
        when(runs.selectCount(any())).thenReturn(100L);
        service.enqueue("member", "正文", "POLISH", "gpt-6-astra");
        verify(runs).insertReadOnlyQueued(any()); verify(runs, never()).selectCount(any());
    }

    ImageItemDto image() { return new ImageItemDto("new-image", "合成.png", "1 KB", "今天", "合成", "/api/images/new-image/raw", "#fff", "#eee"); }
}
