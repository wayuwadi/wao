package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.dto.CreateNoteRequest;
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

    @Test void polishTextStartsBeforeTheImageSlotBecomesAvailable() {
        String body = "今天记录了一个重要的想法，想留待以后对照继续观察它是否成立，也想记下当时的场景和我为什么改变了看法。";
        service.beginIfRequested("m1", note("m1", 1, body),
                new CreateNoteRequest(null, List.of(), null, null, false, true, true));
        ArgumentCaptor<Workflow> inserted = ArgumentCaptor.forClass(Workflow.class);
        verify(workflows).insertIfAbsent(inserted.capture());
        when(workflows.lock("d1")).thenReturn(inserted.getValue());
        DocEntity entity = new DocEntity();
        entity.setId("d1"); entity.setAuthorId("m1"); entity.setKbId(KnowledgeService.NOTES_KB_ID);
        when(docs.selectById("d1")).thenReturn(entity);
        when(knowledge.getDoc("d1")).thenReturn(note("m1", 1, body));
        when(textJobs.submit(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new TextPolishJobService.Result("job1", "PENDING", null, null, null, null));

        service.advance("d1");

        ArgumentCaptor<Workflow> updated = ArgumentCaptor.forClass(Workflow.class);
        verify(workflows).update(updated.capture());
        assertThat(updated.getValue().polishStatus()).isEqualTo("RUNNING");
        assertThat(updated.getValue().polishJobId()).isEqualTo("job1");
        assertThat(updated.getValue().imageStatus()).isEqualTo("PENDING");
        verify(images, never()).start(any(), any());
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
