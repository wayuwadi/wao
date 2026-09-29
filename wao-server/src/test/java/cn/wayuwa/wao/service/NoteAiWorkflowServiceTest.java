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
        assertThat(saved.getValue().titleStatus()).isEqualTo("FAILED");
        assertThat(saved.getValue().imageStatus()).isEqualTo("FAILED");
        assertThat(saved.getValue().polishStatus()).isEqualTo("FAILED");
        verify(knowledge, never()).updateDoc(any(), any());
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

    @Test void failedStageDoesNotSilentlyCancelLaterSelectedStages() {
        Workflow workflow = new Workflow("d1", "m1", 1, "原题", "oldhash", "RUNNING", "job1",
                "PENDING", null, "PENDING", null, null, null, null);
        when(workflows.lock("d1")).thenReturn(workflow);

        service.failCurrent("d1", "当前账号不可用");

        ArgumentCaptor<Workflow> saved = ArgumentCaptor.forClass(Workflow.class);
        verify(workflows).update(saved.capture());
        assertThat(saved.getValue().titleStatus()).isEqualTo("FAILED");
        assertThat(saved.getValue().imageStatus()).isEqualTo("PENDING");
        assertThat(saved.getValue().polishStatus()).isEqualTo("PENDING");
    }

    private static DocDto note(String author, int rev, String markdown) {
        return new DocDto("d1", "原题", author, null, null, false, List.of(), List.of(),
                new RichDocContentDto(1, null, markdown), List.of(), KnowledgeService.NOTES_KB_ID,
                null, rev);
    }
}
