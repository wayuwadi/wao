package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.dto.CreateNoteRequest;
import cn.wayuwa.wao.dto.ArticleImageJobDto;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.RichDocContentDto;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import cn.wayuwa.wao.mapper.NoteAiWorkflowMapper;
import cn.wayuwa.wao.mapper.NoteAiWorkflowMapper.Workflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NoteAiWorkflowServiceTest {
    private final NoteAiWorkflowMapper workflows = mock(NoteAiWorkflowMapper.class);
    private final DocMapper docs = mock(DocMapper.class);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final TextPolishJobService textJobs = mock(TextPolishJobService.class);
    private final ArticleImageService images = mock(ArticleImageService.class);
    private final NotePolishVersionService polish = mock(NotePolishVersionService.class);
    private final MemberPreferenceMapper preferences = mock(MemberPreferenceMapper.class);
    private final NoteAiWorkflowService service = new NoteAiWorkflowService(
            workflows, docs, knowledge, textJobs, images, polish, preferences, new ObjectMapper());

    @Test void localWorkerRecordsStageSubmissionFailureInsteadOfLeavingItPending() {
        String body = "今天记录了一个重要的想法，想留待以后对照继续观察它是否成立，也想记下当时的场景和我为什么改变了看法。".repeat(2);
        when(workflows.lock("d1")).thenReturn(pendingAll("d1", "m1"));
        DocEntity entity = new DocEntity();
        entity.setId("d1"); entity.setAuthorId("m1"); entity.setKbId(KnowledgeService.NOTES_KB_ID);
        when(docs.selectById("d1")).thenReturn(entity);
        when(knowledge.getDoc("d1")).thenReturn(note("m1", 1, body));
        when(textJobs.submit(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new cn.wayuwa.wao.common.BadRequestException("所选模型已不可用，请重新选择"));
        @SuppressWarnings("unchecked")
        var provider = (org.springframework.beans.factory.ObjectProvider<NoteAiWorkflowService>) mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(service);
        var env = new org.springframework.mock.env.MockEnvironment().withProperty("wao.ai.local-notes.document-ids", "d1");
        var worker = new LocalNoteAiService(mock(cn.wayuwa.wao.mapper.AiAssistantRunMapper.class), mock(ImageService.class), workflows, provider, env);

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(worker, "advanceWorkflows");

        ArgumentCaptor<Workflow> saved = ArgumentCaptor.forClass(Workflow.class);
        verify(workflows).update(saved.capture());
        assertThat(saved.getValue().titleStatus()).isEqualTo("FAILED");
        assertThat(saved.getValue().imageStatus()).isEqualTo("PENDING");
        assertThat(saved.getValue().polishStatus()).isEqualTo("PENDING");
        assertThat(saved.getValue().lastError()).contains("标题", "重新选择");
    }

    @Test void noExplicitAiChoiceDoesNotCreateWorkOrSpendQuota() {
        service.beginIfRequested("m1", note("m1", 1, "正文"),
                new CreateNoteRequest(null, List.of(), null, null, false, false, false));
        verify(workflows, never()).insertIfAbsent(any());
    }

    @Test void selectedStagesAreDurableAndBoundToTheAuthor() {
        var request = new CreateNoteRequest(null, List.of(), null, null, true, false, true);
        service.beginIfRequested("m1", note("m1", 1, "今天记录了一段值得保留的经历。"), request);
        service.beginIfRequested("m2", note("m1", 1, "今天记录了一段值得保留的经历。"), request);

        ArgumentCaptor<Workflow> saved = ArgumentCaptor.forClass(Workflow.class);
        verify(workflows).insertIfAbsent(saved.capture());
        assertThat(saved.getValue().memberId()).isEqualTo("m1");
        assertThat(saved.getValue().titleStatus()).isEqualTo("PENDING");
        assertThat(saved.getValue().imageStatus()).isEqualTo("SKIPPED");
        assertThat(saved.getValue().polishStatus()).isEqualTo("PENDING");
    }

    @Test void aLaterManualEditPreventsStaleAiWriteback() {
        Workflow workflow = new Workflow("d1", "m1", 1, "原题", "oldhash", "RUNNING", "job1",
                "PENDING", null, "PENDING", null, null, null, null);
        when(workflows.lock("d1")).thenReturn(workflow);
        DocEntity entity = new DocEntity();
        entity.setId("d1"); entity.setAuthorId("m1"); entity.setKbId(KnowledgeService.NOTES_KB_ID);
        when(docs.selectById("d1")).thenReturn(entity);
        when(knowledge.getDoc("d1")).thenReturn(note("m1", 2, "我已经自己改过正文。"));

        service.advance("d1");

        ArgumentCaptor<Workflow> saved = ArgumentCaptor.forClass(Workflow.class);
        verify(workflows).update(saved.capture());
        assertThat(saved.getValue().titleStatus()).isEqualTo("PENDING");
        assertThat(saved.getValue().imageStatus()).isEqualTo("PENDING");
        assertThat(saved.getValue().polishStatus()).isEqualTo("PENDING");
        assertThat(saved.getValue().titleJobId()).isNull();
        assertThat(saved.getValue().baseRev()).isEqualTo(2);
        verify(knowledge, never()).updateDoc(any(), any());
        verify(textJobs, never()).get(any(), any());
        verify(textJobs, never()).submit(any(), any(), any(), any(), any(), any(), any());
        verify(images, never()).start(any(), any());
    }

    @Test void tagOnlySaveCanAdvanceWithoutDiscardingTheOriginalAiPlan() {
        service.beginIfRequested("m1", note("m1", 1, "正文"),
                new CreateNoteRequest(null, List.of(), null, null, true, false, false));
        ArgumentCaptor<Workflow> inserted = ArgumentCaptor.forClass(Workflow.class);
        verify(workflows).insertIfAbsent(inserted.capture());
        when(workflows.lock("d1")).thenReturn(inserted.getValue());
        DocEntity entity = new DocEntity();
        entity.setId("d1"); entity.setAuthorId("m1"); entity.setKbId(KnowledgeService.NOTES_KB_ID);
        when(docs.selectById("d1")).thenReturn(entity);
        when(knowledge.getDoc("d1")).thenReturn(note("m1", 2, "正文"));

        service.advance("d1");

        ArgumentCaptor<Workflow> updated = ArgumentCaptor.forClass(Workflow.class);
        verify(workflows).update(updated.capture());
        assertThat(updated.getValue().baseRev()).isEqualTo(2);
        assertThat(updated.getValue().titleStatus()).isEqualTo("SKIPPED");
    }

    @Test void defaultParallelModeStartsSelectedStagesTogether() {
        String body = "今天记录了一个重要的想法，想留待以后对照继续观察它是否成立，也想记下当时的场景和我为什么改变了看法。".repeat(2);
        service.beginIfRequested("m1", note("m1", 1, body),
                new CreateNoteRequest(null, List.of(), null, null, true, true, true));
        ArgumentCaptor<Workflow> inserted = ArgumentCaptor.forClass(Workflow.class);
        verify(workflows).insertIfAbsent(inserted.capture());
        when(workflows.lock("d1")).thenReturn(inserted.getValue());
        DocEntity entity = new DocEntity();
        entity.setId("d1"); entity.setAuthorId("m1"); entity.setKbId(KnowledgeService.NOTES_KB_ID);
        when(docs.selectById("d1")).thenReturn(entity);
        when(knowledge.getDoc("d1")).thenReturn(note("m1", 1, body));
        when(textJobs.submit(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new TextPolishJobService.Result("job1", "PENDING", null, null, null, null));
        when(images.startNoteWorkflow(any(), any())).thenReturn(new ArticleImageJobDto("image1", "QUEUED", "等待生成", null));

        service.advance("d1");

        verify(textJobs, org.mockito.Mockito.times(2)).submit(any(), any(), any(), any(), any(), any(), any());
        verify(images).startNoteWorkflow(any(), any());
        ArgumentCaptor<Workflow> updated = ArgumentCaptor.forClass(Workflow.class);
        verify(workflows, org.mockito.Mockito.times(3)).update(updated.capture());
        Workflow finalState = updated.getAllValues().get(2);
        assertThat(finalState.titleStatus()).isEqualTo("RUNNING");
        assertThat(finalState.imageStatus()).isEqualTo("RUNNING");
        assertThat(finalState.polishStatus()).isEqualTo("RUNNING");
    }

    @Test void parallelModeRespectsTheSavedPerMemberConcurrencyLimit() {
        String body = "今天记录了一个重要的想法，想留待以后对照继续观察它是否成立，也想记下当时的场景和我为什么改变了看法。".repeat(2);
        Workflow workflow = pendingAll("d1", "m1");
        when(workflows.lock("d1")).thenReturn(workflow);
        when(workflows.countRunningStages("m1")).thenReturn(0, 1, 2);
        when(preferences.find("m1", "note-ai-defaults-v1"))
                .thenReturn("{\"executionMode\":\"parallel\",\"maxConcurrent\":2}");
        DocEntity entity = new DocEntity();
        entity.setId("d1"); entity.setAuthorId("m1"); entity.setKbId(KnowledgeService.NOTES_KB_ID);
        when(docs.selectById("d1")).thenReturn(entity);
        when(knowledge.getDoc("d1")).thenReturn(note("m1", 1, body));
        when(textJobs.submit(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new TextPolishJobService.Result("job1", "PENDING", null, null, null, null));
        when(images.startNoteWorkflow(any(), any())).thenReturn(new ArticleImageJobDto("image1", "QUEUED", "等待生成", null));

        service.advance("d1");

        verify(textJobs).submit(any(), any(), org.mockito.ArgumentMatchers.eq("NOTE_TITLE"), any(), any(), any(), any());
        verify(images).startNoteWorkflow(any(), any());
        verify(textJobs, never()).submit(any(), any(), org.mockito.ArgumentMatchers.eq("NOTE_BODY"), any(), any(), any(), any());
    }

    @Test void serialPreferenceStartsOnlyTheFirstSelectedStage() {
        String body = "今天记录了一个重要的想法，想留待以后对照继续观察它是否成立，也想记下当时的场景和我为什么改变了看法。".repeat(2);
        Workflow workflow = pendingAll("d1", "m1");
        when(workflows.lock("d1")).thenReturn(workflow);
        when(preferences.find("m1", "note-ai-defaults-v1"))
                .thenReturn("{\"executionMode\":\"serial\",\"maxConcurrent\":5}");
        DocEntity entity = new DocEntity();
        entity.setId("d1"); entity.setAuthorId("m1"); entity.setKbId(KnowledgeService.NOTES_KB_ID);
        when(docs.selectById("d1")).thenReturn(entity);
        when(knowledge.getDoc("d1")).thenReturn(note("m1", 1, body));
        when(textJobs.submit(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new TextPolishJobService.Result("job1", "PENDING", null, null, null, null));

        service.advance("d1");

        verify(textJobs).submit(any(), any(), org.mockito.ArgumentMatchers.eq("NOTE_TITLE"), any(), any(), any(), any());
        verify(images, never()).start(any(), any());
        verify(textJobs, never()).submit(any(), any(), org.mockito.ArgumentMatchers.eq("NOTE_BODY"), any(), any(), any(), any());
    }

    private static Workflow pendingAll(String docId, String memberId) {
        return new Workflow(docId, memberId, 1, "原题", digestForTest("今天记录了一个重要的想法，想留待以后对照继续观察它是否成立，也想记下当时的场景和我为什么改变了看法。".repeat(2)),
                "PENDING", null, "PENDING", null, "PENDING", null, null, null, null);
    }

    private static String digestForTest(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    @Test void polishSubmissionFailureDoesNotCancelRunningTitleOrCompletedImage() {
        Workflow pending = pendingAll("d1", "m1");
        Workflow workflow = new Workflow("d1", "m1", 1, "原题", pending.sourceMarkdownHash(), "RUNNING", "title1",
                "DONE", "image1", "PENDING", null, null, null, null);
        when(workflows.lock("d1")).thenReturn(workflow);
        owned(note("m1", 1, failureBody()));
        when(textJobs.get("m1", "title1")).thenReturn(new TextPolishJobService.Result("title1", "RUNNING", null, null, null, null));
        when(textJobs.submit(any(), any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("不应保存的内部详情"));
        RuntimeException failure = captureAdvanceFailure();
        // 前一阶段的写入在实际事务中已回滚；这里只观察独立失败事务。
        org.mockito.Mockito.clearInvocations(workflows);
        service.failAdvance("d1", failure);
        ArgumentCaptor<Workflow> saved = ArgumentCaptor.forClass(Workflow.class);
        verify(workflows).update(saved.capture());
        assertThat(saved.getValue().titleStatus()).isEqualTo("RUNNING");
        assertThat(saved.getValue().imageStatus()).isEqualTo("DONE");
        assertThat(saved.getValue().polishStatus()).isEqualTo("FAILED");
        assertThat(saved.getValue().lastError()).contains("润色", "重试").doesNotContain("内部详情");
    }

    @Test void failedImageStageRollsBackBeforeRecordingOnlyImageFailure() {
        Workflow w = pendingAll("d1", "m1").withTitle("DONE", "title1", 1, "原题", null);
        when(workflows.lock("d1")).thenReturn(w);
        owned(note("m1", 1, failureBody()));
        when(images.startNoteWorkflow(any(), any())).thenThrow(new cn.wayuwa.wao.common.BadRequestException("模型暂不可用"));
        RuntimeException failure = captureAdvanceFailure();
        verify(workflows, never()).update(any());
        service.failAdvance("d1", failure);
        ArgumentCaptor<Workflow> saved = ArgumentCaptor.forClass(Workflow.class);
        verify(workflows).update(saved.capture());
        assertThat(saved.getValue().imageStatus()).isEqualTo("FAILED");
        assertThat(saved.getValue().polishStatus()).isEqualTo("PENDING");
        assertThat(saved.getValue().lastError()).contains("配图");
    }

    @Test void lateFailureDoesNotOverwriteAWorkflowAlreadyChangedByAnotherRequest() {
        Workflow w = pendingAll("d1", "m1");
        when(workflows.lock("d1")).thenReturn(w);
        owned(note("m1", 1, failureBody()));
        when(textJobs.submit(any(), any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("失败"));
        RuntimeException failure = captureAdvanceFailure();
        when(workflows.lock("d1")).thenReturn(w.withRev(2));
        service.failAdvance("d1", failure);
        verify(workflows, never()).update(any());
    }

    @Test void databaseAndVersionConflictsRemainRecoverableWithoutFalseModelFailure() {
        when(workflows.lock("d1")).thenReturn(pendingAll("d1", "m1"));
        owned(note("m1", 1, failureBody()));
        for (RuntimeException cause : List.of(new org.springframework.dao.DataAccessResourceFailureException("离线"),
                new cn.wayuwa.wao.common.ConflictException("版本变化"))) {
            org.mockito.Mockito.doThrow(cause).when(textJobs).submit(any(), any(), any(), any(), any(), any(), any());
            service.failAdvance("d1", captureAdvanceFailure());
        }
        verify(workflows, never()).update(any());
    }

    @Test void startingPolishPreservesAnUnresolvedTitleFailureReason() {
        Workflow w = pendingAll("d1", "m1").withTitle("FAILED", "title1", 1, "原题", "标题模型暂不可用")
                .withImage("DONE", "image1", 1, digestForTest(failureBody()), "标题模型暂不可用");
        when(workflows.lock("d1")).thenReturn(w);
        owned(note("m1", 1, failureBody()));
        when(textJobs.submit(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new TextPolishJobService.Result("polish1", "PENDING", null, null, null, null));
        service.advance("d1");
        ArgumentCaptor<Workflow> saved = ArgumentCaptor.forClass(Workflow.class);
        verify(workflows).update(saved.capture());
        assertThat(saved.getValue().polishStatus()).isEqualTo("RUNNING");
        assertThat(saved.getValue().titleStatus()).isEqualTo("FAILED");
        assertThat(saved.getValue().lastError()).isEqualTo("标题模型暂不可用");
    }

    @Test void editingOnlyTheTitlePreservesUnresolvedImageFailureReason() {
        Workflow w = pendingAll("d1", "m1").withImage("FAILED", "image1", 1,
                digestForTest(failureBody()), "配图模型暂不可用");
        when(workflows.lock("d1")).thenReturn(w);
        DocDto doc = note("m1", 2, failureBody());
        owned(new DocDto(doc.id(), "手写标题", doc.author(), null, null, false, List.of(), List.of(),
                doc.content(), List.of(), KnowledgeService.NOTES_KB_ID, null, 2));
        service.refreshAfterSave("m1", "d1");
        ArgumentCaptor<Workflow> saved = ArgumentCaptor.forClass(Workflow.class);
        verify(workflows).update(saved.capture());
        assertThat(saved.getValue().titleStatus()).isEqualTo("SKIPPED");
        assertThat(saved.getValue().imageStatus()).isEqualTo("FAILED");
        assertThat(saved.getValue().lastError()).isEqualTo("配图模型暂不可用");
    }

    @Test void retryClearsFailureReasonWithoutRestartingCompletedStages() {
        Workflow w = pendingAll("d1", "m1").withImage("DONE", "image1", 1,
                digestForTest(failureBody()), null).withPolish("DONE", "polish1", null)
                .withTitle("FAILED", "title1", 1, "原题", "标题模型暂不可用");
        when(workflows.lock("d1")).thenReturn(w);
        owned(note("m1", 1, failureBody()));
        var result = service.retry("m1", "d1");
        assertThat(result.title()).isEqualTo("PENDING");
        assertThat(result.image()).isEqualTo("DONE");
        assertThat(result.polish()).isEqualTo("DONE");
        assertThat(result.message()).isNull();
    }

    @Test void clearingOneOfSeveralFailuresDoesNotMisattributeItsOldReason() {
        Workflow w = pendingAll("d1", "m1").withImage("FAILED", "image1", 1,
                digestForTest(failureBody()), "配图失败").withTitle("FAILED", "title1", 1, "原题", "标题失败");
        when(workflows.lock("d1")).thenReturn(w);
        DocDto doc = note("m1", 2, failureBody());
        owned(new DocDto(doc.id(), "手写标题", doc.author(), null, null, false, List.of(), List.of(),
                doc.content(), List.of(), KnowledgeService.NOTES_KB_ID, null, 2));
        service.refreshAfterSave("m1", "d1");
        ArgumentCaptor<Workflow> saved = ArgumentCaptor.forClass(Workflow.class);
        verify(workflows).update(saved.capture());
        assertThat(saved.getValue().titleStatus()).isEqualTo("SKIPPED");
        assertThat(saved.getValue().imageStatus()).isEqualTo("FAILED");
        assertThat(saved.getValue().lastError()).contains("失败阶段重试").doesNotContain("标题失败");
    }

    private RuntimeException captureAdvanceFailure() {
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> service.advance("d1"));
        assertThat(failure).isInstanceOf(RuntimeException.class);
        return (RuntimeException) failure;
    }

    private static String failureBody() { return "今天记录了一个重要的想法，想留待以后对照继续观察它是否成立，也想记下当时的场景和我为什么改变了看法。".repeat(2); }

    private static DocDto note(String author, int rev, String markdown) {
        return new DocDto("d1", "原题", author, null, null, false, List.of(), List.of(),
                new RichDocContentDto(1, null, markdown), List.of(), KnowledgeService.NOTES_KB_ID,
                null, rev);
    }

    @Test void saveLatestBodyRestartsPolishAndPreservesCompletedTitleAndImage() {
        Workflow w = new Workflow("d1", "m1", 1, "原题", digestForTest("旧正文"),
                "DONE", "title1", "DONE", "image1", "DONE", "oldPolish", null, null, null);
        when(workflows.lock("d1")).thenReturn(w);
        owned(note("m1", 4, "旧正文加上刚刚继续写的新内容。"));
        service.refreshAfterSave("m1", "d1");
        ArgumentCaptor<Workflow> saved = ArgumentCaptor.forClass(Workflow.class);
        verify(workflows).update(saved.capture());
        assertThat(saved.getValue().polishStatus()).isEqualTo("PENDING");
        assertThat(saved.getValue().polishJobId()).isNull();
        assertThat(saved.getValue().sourceMarkdownHash()).isEqualTo(digestForTest("旧正文加上刚刚继续写的新内容。"));
        assertThat(saved.getValue().titleStatus()).isEqualTo("DONE");
        assertThat(saved.getValue().imageStatus()).isEqualTo("DONE");
    }

    @Test void otherMemberCannotRestartAndUnselectedStagesStaySkipped() {
        Workflow w = new Workflow("d1", "m1", 1, "原题", "oldhash", "RUNNING", "oldTitle",
                "SKIPPED", null, "SKIPPED", null, null, null, null);
        when(workflows.lock("d1")).thenReturn(w);
        service.refreshAfterSave("m2", "d1");
        verify(workflows, never()).update(any());
        DocDto body = note("m1", 4, "本人最新正文");
        owned(new DocDto(body.id(), "本人最新标题", body.author(), null, null, false, List.of(), List.of(),
                body.content(), List.of(), KnowledgeService.NOTES_KB_ID, null, 4));
        service.refreshAfterSave("m1", "d1");
        ArgumentCaptor<Workflow> saved = ArgumentCaptor.forClass(Workflow.class);
        verify(workflows).update(saved.capture());
        assertThat(saved.getValue().titleStatus()).isEqualTo("SKIPPED");
        assertThat(saved.getValue().imageStatus()).isEqualTo("SKIPPED");
        assertThat(saved.getValue().polishStatus()).isEqualTo("SKIPPED");
    }

    @Test void polishWritesSingleLatestBodyWithCoverAndTracksItsNewRevision() {
        String cover = "![文章总览图](/api/images/cover/raw)\n\n";
        String original = "今天记录了一段值得保留的经历，先明确项目的目标，然后列出行动计划，并且继续观察结果。";
        String source = cover + original;
        Workflow w = new Workflow("d1", "m1", 4, "原题", digestForTest(source), "DONE", "title1",
                "DONE", "image1", "RUNNING", "polish1", null, null, null);
        when(workflows.lock("d1")).thenReturn(w);
        owned(note("m1", 4, source));
        when(textJobs.get("m1", "polish1")).thenReturn(new TextPolishJobService.Result("polish1", "SUCCEEDED",
                null, "今天明确了项目目标，并列出行动计划，接下来持续观察执行结果。", null, null));
        String finalBody = cover + "今天明确了项目目标，并列出行动计划，接下来持续观察执行结果。";
        when(knowledge.updateDoc(any(), any())).thenReturn(note("m1", 5, finalBody));
        service.advance("d1");
        ArgumentCaptor<cn.wayuwa.wao.dto.PatchDocRequest> patch = ArgumentCaptor.forClass(cn.wayuwa.wao.dto.PatchDocRequest.class);
        verify(knowledge).updateDoc(org.mockito.ArgumentMatchers.eq("d1"), patch.capture());
        assertThat(patch.getValue().expectedRev()).isEqualTo(4);
        assertThat(patch.getValue().content().markdown()).isEqualTo(finalBody);
        ArgumentCaptor<Workflow> saved = ArgumentCaptor.forClass(Workflow.class);
        verify(workflows).update(saved.capture());
        assertThat(saved.getValue().polishStatus()).isEqualTo("DONE");
        assertThat(saved.getValue().baseRev()).isEqualTo(5);
        assertThat(saved.getValue().sourceMarkdownHash()).isEqualTo(digestForTest(finalBody));
    }

    private void owned(DocDto doc) {
        DocEntity entity = new DocEntity();
        entity.setId(doc.id()); entity.setAuthorId(doc.author()); entity.setKbId(doc.kbId());
        when(docs.selectById(doc.id())).thenReturn(entity);
        when(knowledge.getDoc(doc.id())).thenReturn(doc);
    }

    @Test void retryAfterBodyChangesCannotReuseOldRunningPolishResult() {
        when(workflows.lock("d1")).thenReturn(new Workflow("d1", "m1", 1, "原题", "oldhash",
                "DONE", "title1", "DONE", "image1", "RUNNING", "stalePolish", null, null, null));
        owned(note("m1", 4, "本人最新输入，不可由旧正文的润色结果覆盖。"));
        service.retry("m1", "d1");
        ArgumentCaptor<Workflow> saved = ArgumentCaptor.forClass(Workflow.class);
        verify(workflows).update(saved.capture());
        assertThat(saved.getValue().polishStatus()).isEqualTo("PENDING");
        assertThat(saved.getValue().polishJobId()).isNull();
        assertThat(saved.getValue().baseRev()).isEqualTo(4);
    }
}
