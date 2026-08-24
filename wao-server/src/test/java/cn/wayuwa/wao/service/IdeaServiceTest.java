package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.IdeaEntity;
import cn.wayuwa.wao.domain.IdeaTaskLinkEntity;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.CreateProjectRequest;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.IdeaDto;
import cn.wayuwa.wao.dto.ProjectDto;
import cn.wayuwa.wao.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdeaServiceTest {
    private final IdeaMapper ideas = mock(IdeaMapper.class);
    private final IdeaArtifactMapper artifacts = mock(IdeaArtifactMapper.class);
    private final IdeaTaskLinkMapper links = mock(IdeaTaskLinkMapper.class);
    private final IdeaIterationMapper iterations = mock(IdeaIterationMapper.class);
    private final IdeaResearchRunMapper research = mock(IdeaResearchRunMapper.class);
    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final MemberMapper members = mock(MemberMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final AiTaskRunMapper aiRuns = mock(AiTaskRunMapper.class);
    private final TaskService taskService = mock(TaskService.class);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private IdeaService service;

    @BeforeEach
    void setUp() {
        when(artifacts.selectList(any())).thenReturn(List.of());
        when(links.selectList(any())).thenReturn(List.of());
        when(iterations.selectList(any())).thenReturn(List.of());
        when(research.selectList(any())).thenReturn(List.of());
        service = new IdeaService(ideas, artifacts, links, iterations, research, taskMapper,
                members, projects, aiRuns, taskService, knowledge, projectService, json);
    }

    @Test
    void quickCaptureDefaultsCreatorAsOwnerAndDoesNotApplyWipLimit() {
        IdeaDto first = service.create(new IdeaDto.CreateRequest("第一个点子", "一句话", "P1"), "wg");
        IdeaDto second = service.create(new IdeaDto.CreateRequest("第二个点子", null, "P0"), "wg");

        assertThat(first.stage()).isEqualTo("INBOX");
        assertThat(first.ownerId()).isEqualTo("wg");
        assertThat(second.priority()).isEqualTo("P0");
        verify(ideas, org.mockito.Mockito.times(2)).insert(any(IdeaEntity.class));
    }

    @Test
    void anyMemberCanTransferResponsibilityToARealMember() {
        IdeaEntity idea = idea("INBOX", emptyDetail());
        when(ideas.selectById("i1")).thenReturn(idea);
        when(members.selectById("wd")).thenReturn(new MemberEntity());

        IdeaDto updated = service.patch("i1", new IdeaDto.PatchRequest(null, null, null, null,
                null, "wd", null, null), "another-member");

        assertThat(updated.ownerId()).isEqualTo("wd");
        assertThat(updated.detail().activity()).anyMatch(a -> a.by().equals("another-member"));
    }

    @Test
    void thinkingMustDefineMvpHypothesisBeforeAlignment() {
        IdeaEntity idea = idea("THINKING", emptyDetail());
        when(ideas.selectById("i1")).thenReturn(idea);

        assertThatThrownBy(() -> service.transition("i1",
                new IdeaDto.TransitionRequest("ALIGNING", null, null, null), "wd"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("MVP");
    }

    @Test
    void startedIdeaCannotDisappearWithoutAbandonmentReason() {
        IdeaEntity idea = idea("RESEARCHING", emptyDetail());
        when(ideas.selectById("i1")).thenReturn(idea);

        assertThatThrownBy(() -> service.transition("i1",
                new IdeaDto.TransitionRequest("ABANDONED", null, null, ""), "wd"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("原因");
    }

    @Test
    void conversionReusesLinkedProjectAndCreatesOneArchive() {
        IdeaEntity idea = idea("MVP_BUILDING", emptyDetail());
        idea.setProjectId("p1");
        when(ideas.selectById("i1")).thenReturn(idea);
        cn.wayuwa.wao.dto.ProjectDto project = mock(cn.wayuwa.wao.dto.ProjectDto.class);
        when(project.id()).thenReturn("p1");
        when(project.name()).thenReturn("桌面应用");
        when(projectService.get("p1", "wg")).thenReturn(project);
        cn.wayuwa.wao.dto.DocDto archive = mock(cn.wayuwa.wao.dto.DocDto.class);
        when(archive.id()).thenReturn("d-archive");
        when(archive.title()).thenReturn("原始点子与迭代历史");
        when(knowledge.ensureIdeaArchive(any(), any(), any())).thenReturn(archive);

        assertThat(service.convertToProject("i1", new IdeaDto.ConvertRequest(null), "wg").id()).isEqualTo("p1");

        verify(knowledge).ensureIdeaArchive(any(), any(), any());
        verify(ideas).updateById(idea);
    }

    @Test
    void conversionDoesNotReuseOneProjectWhenAnotherLinkedTaskIsUnassigned() {
        IdeaEntity idea = idea("INBOX", emptyDetail());
        IdeaTaskLinkEntity firstLink = taskLink("l1", "t1", 1);
        IdeaTaskLinkEntity secondLink = taskLink("l2", "t2", 2);
        TaskEntity assigned = task("t1", "p1");
        TaskEntity unassigned = task("t2", null);
        when(ideas.selectById("i1")).thenReturn(idea);
        when(links.selectList(any())).thenReturn(List.of(firstLink, secondLink));
        when(taskMapper.selectById("t1")).thenReturn(assigned);
        when(taskMapper.selectById("t2")).thenReturn(unassigned);
        ProjectDto created = mock(ProjectDto.class);
        when(created.id()).thenReturn("p-new");
        when(created.name()).thenReturn("点子");
        when(projectService.create(any(CreateProjectRequest.class), any())).thenReturn(created);
        DocDto archive = mock(DocDto.class);
        when(archive.id()).thenReturn("d-archive");
        when(archive.title()).thenReturn("原始点子与迭代历史");
        when(knowledge.ensureIdeaArchive(any(), any(), any())).thenReturn(archive);

        assertThat(service.convertToProject("i1", null, "wg").id()).isEqualTo("p-new");

        org.mockito.ArgumentCaptor<CreateProjectRequest> request =
                org.mockito.ArgumentCaptor.forClass(CreateProjectRequest.class);
        verify(projectService).create(request.capture(), org.mockito.ArgumentMatchers.eq("wg"));
        assertThat(request.getValue().announce()).isFalse();
    }

    private static IdeaTaskLinkEntity taskLink(String id, String taskId, int order) {
        IdeaTaskLinkEntity link = new IdeaTaskLinkEntity();
        link.setId(id); link.setIdeaId("i1"); link.setTaskId(taskId); link.setOrd(order);
        link.setRequiredMvp(false); link.setCreatedBy("wg"); link.setCreatedAt(LocalDateTime.now());
        return link;
    }

    private static TaskEntity task(String id, String projectId) {
        TaskEntity task = new TaskEntity();
        task.setId(id); task.setProjectId(projectId); task.setTitle("任务 " + id); task.setStatus("待开始");
        return task;
    }

    private IdeaEntity idea(String stage, IdeaDto.Detail detail) {
        IdeaEntity e = new IdeaEntity();
        e.setId("i1"); e.setTitle("点子"); e.setRawIdea(""); e.setProblemText(""); e.setTargetOutcome("");
        e.setPriority("P1"); e.setStage(stage); e.setCreatedBy("wg"); e.setOwnerId("wg");
        e.setCreatedAt(LocalDateTime.now()); e.setUpdatedAt(LocalDateTime.now());
        try { e.setDetailJson(json.writeValueAsString(detail)); } catch (Exception ex) { throw new AssertionError(ex); }
        return e;
    }

    private static IdeaDto.Detail emptyDetail() {
        return new IdeaDto.Detail("", "", "", "", "", "", "", null, List.of(),
                "", "", "", "", "", null, null, List.of());
    }
}
