package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.dto.CreateTaskRequest;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.KbDto;
import cn.wayuwa.wao.dto.PatchDocRequest;
import cn.wayuwa.wao.dto.TaskDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkspaceMcpToolsTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ProjectService projects = mock(ProjectService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final WorkspaceMcpTools tools = new WorkspaceMcpTools(projects, tasks, knowledge, json);
    private final WorkspaceMcpTokenService.AuthenticatedToken writer =
            new WorkspaceMcpTokenService.AuthenticatedToken("token", "wd", true);

    @Test
    void unfinishedTaskListDefaultsToCurrentOwnerAndVisibleProjects() throws Exception {
        TaskDto mine = task("mine", "wd", "待办");
        TaskDto others = task("other", "wg", "待办");
        TaskDto done = task("done", "wd", "已完成");
        when(tasks.list("wd")).thenReturn(List.of(mine, others, done));

        @SuppressWarnings("unchecked")
        List<WorkspaceMcpTools.TaskSummary> result = (List<WorkspaceMcpTools.TaskSummary>) tools.call("list_open_tasks", json.readTree("{}"), writer);

        assertThat(result).extracting(WorkspaceMcpTools.TaskSummary::id).containsExactly("mine");
        verify(tasks).list("wd");
    }

    @Test
    void projectKnowledgeAndDocumentAreCheckedAgainstTokenMember() throws Exception {
        KbDto kb = mock(KbDto.class);
        when(kb.id()).thenReturn("kb1");
        when(knowledge.getKb("kb1")).thenReturn(kb);
        when(knowledge.projectIdForKb("kb1")).thenReturn("hidden-project");
        org.mockito.Mockito.doThrow(new NotFoundException("项目不存在"))
                .when(projects).requireAccess("hidden-project", "wd");
        DocDto doc = mock(DocDto.class);
        when(doc.kbId()).thenReturn("kb1");
        when(knowledge.getDoc("d1")).thenReturn(doc);

        assertThatThrownBy(() -> tools.call("get_document",
                json.readTree("{\"document_id\":\"d1\"}"), writer))
                .isInstanceOf(NotFoundException.class);
        verify(knowledge, never()).updateDoc(eq("d1"), any());
    }

    @Test
    void readOnlyTokenCannotReachWriteServiceEvenByCallingHiddenName() throws Exception {
        var reader = new WorkspaceMcpTokenService.AuthenticatedToken("token", "wd", false);
        assertThatThrownBy(() -> tools.call("create_project",
                json.readTree("{\"name\":\"新项目\"}"), reader))
                .hasMessageContaining("没有写权限");
        verify(projects, never()).create(any(), any());
    }

    @Test
    void createTaskBindsOwnerAndCreatorToAuthenticatedMember() throws Exception {
        tools.call("create_task", json.readTree("""
                {"project_id":"p1","title":"补齐验收"}
                """), writer);

        ArgumentCaptor<CreateTaskRequest> request = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(tasks).create(request.capture(), eq("wd"));
        assertThat(request.getValue().owner()).isEqualTo("wd");
        assertThat(request.getValue().project()).isEqualTo("p1");
    }

    @Test
    void documentBodyReplacementNeedsKnownRevision() throws Exception {
        DocDto doc = mock(DocDto.class);
        when(doc.kbId()).thenReturn("kb1"); when(doc.id()).thenReturn("d1");
        when(doc.rev()).thenReturn(3);
        when(knowledge.getDoc("d1")).thenReturn(doc);
        KbDto kb = mock(KbDto.class);
        when(kb.id()).thenReturn("kb1");
        when(knowledge.getKb("kb1")).thenReturn(kb);

        assertThatThrownBy(() -> tools.call("update_document", json.readTree("""
                {"document_id":"d1","blocks":[]}
                """), writer)).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("expected_rev");
        verify(knowledge, never()).updateDoc(any(), any());

        tools.call("update_document", json.readTree("""
                {"document_id":"d1","blocks":[],"expected_rev":3}
                """), writer);
        ArgumentCaptor<PatchDocRequest> patch = ArgumentCaptor.forClass(PatchDocRequest.class);
        verify(knowledge).updateDoc(eq("d1"), patch.capture());
        assertThat(patch.getValue().expectedRev()).isEqualTo(3);
    }

    private TaskDto task(String id, String owner, String status) {
        TaskDto task = mock(TaskDto.class);
        when(task.id()).thenReturn(id); when(task.owner()).thenReturn(owner);
        when(task.status()).thenReturn(status); when(task.title()).thenReturn(id);
        return task;
    }
}
