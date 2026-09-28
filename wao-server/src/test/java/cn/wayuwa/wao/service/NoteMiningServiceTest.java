package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.dto.ArticleImageJobDto;
import cn.wayuwa.wao.dto.ArticleImageRequest;
import cn.wayuwa.wao.mapper.NoteMiningMapper;
import cn.wayuwa.wao.mapper.NoteMiningVisualMapper;
import cn.wayuwa.wao.mapper.NoteMiningVisualMapper.Visual;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import cn.wayuwa.wao.mapper.NoteMiningMapper.Artifact;
import cn.wayuwa.wao.mapper.NoteMiningMapper.Job;
import cn.wayuwa.wao.mapper.NoteMiningMapper.Source;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NoteMiningServiceTest {
    private static final String REQUEST = "0123456789abcdef0123456789abcdef";
    private static final String JOB_ID = "nm" + REQUEST;
    private final NoteMiningMapper mapper = mock(NoteMiningMapper.class);
    private final NoteMiningVisualMapper visuals = mock(NoteMiningVisualMapper.class);
    private final AiAssistantService assistant = mock(AiAssistantService.class);
    private final ArticleImageService images = mock(ArticleImageService.class);
    private final MemberPreferenceMapper preferences = mock(MemberPreferenceMapper.class);
    private final NoteMiningService service = new NoteMiningService(mapper, visuals, assistant, images, preferences, new ObjectMapper());

    @Test void requiresTwoMeaningfulNotesBeforeStarting() {
        when(mapper.lockMember("m1")).thenReturn("m1");
        when(mapper.recentNotes("m1", 12)).thenReturn(List.of(note("d1", "只有一篇", "原文", 2)));

        assertThatThrownBy(() -> service.start("m1", REQUEST)).hasMessageContaining("至少需要两篇");
        verify(assistant, never()).enqueueReadOnlyJob(any(), any());
    }

    @Test void queuesAuthorizedSnapshotsWithoutChangingOriginalNotes() {
        DocEntity first = note("d1", "晨间", "今天连续忘记喝水", 3);
        DocEntity second = note("d2", "夜间", "眼睛酸涩，后来站起来走了走", 4);
        when(mapper.lockMember("m1")).thenReturn("m1");
        when(mapper.recentNotes("m1", 12)).thenReturn(List.of(first, second));
        when(assistant.enqueueReadOnlyJob(eq(JOB_ID), contains("ARTICLE_SEED"))).thenReturn("air1");
        when(assistant.readOnlyJobResult(JOB_ID, "air1"))
                .thenReturn(new AiAssistantService.ReadOnlyJobResult("air1", "secure_queued", null, null));
        when(mapper.findOwnedJob(JOB_ID, "m1"))
                .thenReturn(null)
                .thenReturn(new Job(JOB_ID, "m1", "[]", 2, "air1", null, null, LocalDateTime.now()));

        var result = service.start("m1", REQUEST);

        assertThat(result.job().status()).isEqualTo("QUEUED");
        ArgumentCaptor<Job> saved = ArgumentCaptor.forClass(Job.class);
        verify(mapper).insertJob(saved.capture());
        assertThat(saved.getValue().sourceSnapshotJson()).contains("晨间", "今天连续忘记喝水");
        assertThat(first.getContentMarkdown()).isEqualTo("今天连续忘记喝水");
        assertThat(second.getContentMarkdown()).isEqualTo("眼睛酸涩，后来站起来走了走");
    }

    @Test void materializesAllThreeContentTypesWithTraceableSources() {
        String snapshot = """
                [{"id":"d1","title":"晨间","rev":3,"excerpt":"忘记喝水"},
                 {"id":"d2","title":"夜间","rev":4,"excerpt":"眼睛酸涩"}]
                """;
        Job job = new Job(JOB_ID, "m1", snapshot, 2, "air1", null, null, LocalDateTime.now());
        when(mapper.lockOwnedJob(JOB_ID, "m1")).thenReturn(job);
        when(mapper.findOwnedJob(JOB_ID, "m1")).thenReturn(job);
        when(mapper.artifacts(JOB_ID, "m1"))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        artifact("a1", "INSIGHT"), artifact("a2", "REMINDER"), artifact("a3", "ARTICLE_SEED")));
        when(mapper.sources(any())).thenAnswer(invocation -> List.of(
                new Source(invocation.getArgument(0), "d1", 3, "晨间", "忘记喝水", 0)));
        when(assistant.readOnlyJobResult(JOB_ID, "air1")).thenReturn(new AiAssistantService.ReadOnlyJobResult(
                "air1", "succeeded", """
                {"artifacts":[
                  {"type":"INSIGHT","content":"洞察","rationale":"反复出现","sourceIndexes":[1,2]},
                  {"type":"REMINDER","content":"喝口水吧","rationale":"容易忘记","sourceIndexes":[1]},
                  {"type":"ARTICLE_SEED","content":"身体发出的信号","rationale":"可展开","sourceIndexes":[2]}
                ],"visualIdeas":[
                  {"lens":"PROGRESS","caption":"两次观察之间的变化","rationale":"两篇都提到身体感受","sourceIndexes":[1,2]},
                  {"lens":"REFRAME","caption":"换个角度看","rationale":"不同时间的记录可对照","sourceIndexes":[1,2]},
                  {"lens":"NEXT_STEP","caption":"下次继续观察","rationale":"从夜间记录出发","sourceIndexes":[2]}
                ]}
                """, null));

        var result = service.get("m1", JOB_ID);

        assertThat(result.job().status()).isEqualTo("SUCCEEDED");
        assertThat(result.artifacts()).extracting(item -> item.type())
                .containsExactly("INSIGHT", "REMINDER", "ARTICLE_SEED");
        verify(mapper).markMaterialized(JOB_ID);
        verify(mapper, times(3)).insertArtifact(any());
        verify(mapper, times(4)).insertSource(any());
        ArgumentCaptor<Visual> visualCaptor = ArgumentCaptor.forClass(Visual.class);
        verify(visuals, times(3)).insert(visualCaptor.capture());
        assertThat(visualCaptor.getAllValues()).extracting(Visual::lens)
                .containsExactly("PROGRESS", "REFRAME", "NEXT_STEP");
        assertThat(visualCaptor.getAllValues().get(0).sourceIndexesJson()).isEqualTo("[0,1]");
    }

    @Test void ignoresVisualsWithoutRealSourcesInsteadOfInventingAttribution() {
        String snapshot = "[{\"id\":\"d1\",\"title\":\"一\",\"rev\":1,\"excerpt\":\"甲\"},"
                + "{\"id\":\"d2\",\"title\":\"二\",\"rev\":1,\"excerpt\":\"乙\"}]";
        Job job = new Job(JOB_ID, "m1", snapshot, 2, "air1", null, null, LocalDateTime.now());
        when(mapper.lockOwnedJob(JOB_ID, "m1")).thenReturn(job);
        when(mapper.findOwnedJob(JOB_ID, "m1")).thenReturn(job);
        when(assistant.readOnlyJobResult(JOB_ID, "air1")).thenReturn(new AiAssistantService.ReadOnlyJobResult(
                "air1", "succeeded", """
                {"artifacts":[
                  {"type":"INSIGHT","content":"甲","rationale":"甲","sourceIndexes":[1]},
                  {"type":"REMINDER","content":"乙","rationale":"乙","sourceIndexes":[2]},
                  {"type":"ARTICLE_SEED","content":"甲乙","rationale":"甲乙","sourceIndexes":[1,2]}],
                 "visualIdeas":[
                  {"lens":"PROGRESS","caption":"甲","rationale":"甲","sourceIndexes":[99]},
                  {"lens":"REFRAME","caption":"乙","rationale":"乙","sourceIndexes":[2]},
                  {"lens":"NEXT_STEP","caption":"甲乙","rationale":"甲乙","sourceIndexes":[1,2]}]}
                """, null));
        service.get("m1", JOB_ID);
        verify(visuals, never()).insert(any());
    }

    @Test void anotherMemberCannotRetryPrivateVisual() {
        assertThatThrownBy(() -> service.retryVisual("m2", JOB_ID, "PROGRESS"))
                .hasMessageContaining("不存在");
        verifyNoInteractions(visuals, images);
    }

    @Test void usesMembersExistingModelAndOnlyGroundedExcerptsForOneImage() {
        String snapshot = "[{\"id\":\"d1\",\"title\":\"晨间\",\"rev\":3,\"excerpt\":\"忘记喝水\"},"
                + "{\"id\":\"d2\",\"title\":\"夜间\",\"rev\":4,\"excerpt\":\"眼睛酸涩\"}]";
        Job job = new Job(JOB_ID, "m1", snapshot, 2, "air1", LocalDateTime.now(), null, LocalDateTime.now());
        Visual visual = new Visual(JOB_ID, "m1", "PROGRESS", "两次记录的变化", "两篇内容可比较",
                "[0,1]", null, null, "PENDING", null, "CANDIDATE", null, null);
        when(visuals.lock(JOB_ID, "PROGRESS")).thenReturn(visual);
        when(mapper.findOwnedJob(JOB_ID, "m1")).thenReturn(job);
        when(preferences.find("m1", "note-ai-model-v1")).thenReturn("gpt-6-sol");
        when(images.start(eq("m1"), any())).thenReturn(new ArticleImageJobDto("run1", "QUEUED", "等待", null));

        service.advanceVisual(JOB_ID, "PROGRESS");

        ArgumentCaptor<ArticleImageRequest> request = ArgumentCaptor.forClass(ArticleImageRequest.class);
        verify(images).start(eq("m1"), request.capture());
        assertThat(request.getValue().model()).isEqualTo("gpt-6-sol");
        assertThat(request.getValue().articleText()).contains("晨间：忘记喝水", "夜间：眼睛酸涩");
        verify(visuals).updateImage(JOB_ID, "m1", "PROGRESS", "RUNNING", "run1", null, null);
    }

    @Test void completedJobsRemainReadableWithoutDependingOnExpiredAiRun() {
        Job completed = new Job(JOB_ID, "m1", "[]", 2, "expired", LocalDateTime.now(), null, LocalDateTime.now());
        when(mapper.latestJob("m1")).thenReturn(completed);
        when(mapper.lockOwnedJob(JOB_ID, "m1")).thenReturn(completed);
        when(mapper.findOwnedJob(JOB_ID, "m1")).thenReturn(completed);
        when(mapper.artifacts(JOB_ID, "m1")).thenReturn(List.of(artifact("a1", "INSIGHT")));
        when(mapper.sources("a1")).thenReturn(List.of());

        assertThat(service.latest("m1").job().status()).isEqualTo("SUCCEEDED");
        verifyNoInteractions(assistant);
    }

    @Test void duplicateStartReusesTheOwnedJobWithoutReadingNotesAgain() {
        Job completed = new Job(JOB_ID, "m1", "[]", 2, "air1", LocalDateTime.now(), null, LocalDateTime.now());
        when(mapper.lockMember("m1")).thenReturn("m1");
        when(mapper.findOwnedJob(JOB_ID, "m1")).thenReturn(completed);
        when(mapper.lockOwnedJob(JOB_ID, "m1")).thenReturn(completed);
        when(mapper.artifacts(JOB_ID, "m1")).thenReturn(List.of(artifact("a1", "INSIGHT")));
        when(mapper.sources("a1")).thenReturn(List.of());

        assertThat(service.start("m1", REQUEST).job().status()).isEqualTo("SUCCEEDED");
        verify(mapper, never()).recentNotes(any(), anyInt());
        verifyNoInteractions(assistant);
    }

    @Test void feedbackRequiresOwnedArtifactAndReturnsUpdatedState() {
        Artifact updated = new Artifact("a1", JOB_ID, "m1", "REMINDER", "喝水", "重复主题",
                "REMINDER_DRAFT", LocalDateTime.now(), LocalDateTime.now());
        when(mapper.updateFeedback("a1", "m1", "REMINDER_DRAFT")).thenReturn(1);
        when(mapper.artifactsForId("a1", "m1")).thenReturn(
                new Artifact("a1", JOB_ID, "m1", "REMINDER", "喝水", "重复主题",
                        "CANDIDATE", LocalDateTime.now(), LocalDateTime.now()), updated);
        when(mapper.sources("a1")).thenReturn(List.of());

        assertThat(service.feedback("m1", "a1", "REMINDER_DRAFT").feedbackStatus())
                .isEqualTo("REMINDER_DRAFT");
        assertThatThrownBy(() -> service.feedback("m1", "a1", "IGNORED"))
                .hasMessageContaining("反馈类型无效");
        when(mapper.artifactsForId("a2", "m1")).thenReturn(artifact("a2", "INSIGHT"));
        assertThatThrownBy(() -> service.feedback("m1", "a2", "REMINDER_DRAFT"))
                .hasMessageContaining("只有提醒候选");
        verify(mapper, never()).updateFeedback("a2", "m1", "REMINDER_DRAFT");
    }

    private static DocEntity note(String id, String title, String body, int rev) {
        DocEntity note = new DocEntity();
        note.setId(id); note.setTitle(title); note.setContentMarkdown(body); note.setRev(rev);
        return note;
    }

    private static Artifact artifact(String id, String type) {
        return new Artifact(id, JOB_ID, "m1", type, type + " 内容", "生成理由", "CANDIDATE",
                LocalDateTime.now(), LocalDateTime.now());
    }
}
