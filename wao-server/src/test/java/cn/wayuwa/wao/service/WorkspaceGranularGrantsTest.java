package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.WorkspaceMcpTokenEntity;
import cn.wayuwa.wao.dto.CreateWorkspaceMcpTokenRequest;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.KbDto;
import cn.wayuwa.wao.mapper.WorkspaceMcpTokenMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkspaceGranularGrantsTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ProjectService projects = mock(ProjectService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final WorkspaceMcpTools tools = new WorkspaceMcpTools(projects, tasks, knowledge, json);

    private WorkspaceMcpTokenService.AuthenticatedToken grant(List<String> operations, List<String> projectIds, List<String> kbIds) {
        var policy = new WorkspaceCapabilityPolicy(operations, projectIds, kbIds);
        return new WorkspaceMcpTokenService.AuthenticatedToken("t", "wd", policy.canWrite(), policy.permits("publish_html"), policy);
    }

    @Test void invalidAndFuturePermissionsNeverFallBackToWholeAccess() {
        for (var values : List.of(List.<String>of(), List.of("future_tool"), List.of("get_task", "get_task"))) {
            assertThatThrownBy(() -> new WorkspaceCapabilityPolicy(values, null, null)).hasMessageContaining("有效操作");
        }
        assertThatThrownBy(() -> new WorkspaceCapabilityPolicy(List.of("get_task"), List.of(), null)).hasMessageContaining("指定范围");
        assertThatThrownBy(() -> new WorkspaceCapabilityPolicy(List.of("get_task"), null, List.of(" kb"))).hasMessageContaining("指定范围");
        assertThat(new WorkspaceMcpTokenService.AuthenticatedToken("old", "wd", true).permits("future_tool")).isFalse();
    }

    @Test void visibleToolsAndDirectCallsUseTheSameAllowList() throws Exception {
        var token = grant(List.of("get_task", "update_task"), null, null);
        assertThat(tools.list(token).path("tools")).hasSize(2);
        assertThat(tools.list(token).toString()).contains("get_task", "update_task").doesNotContain("create_task");
        assertThatThrownBy(() -> tools.call("create_task", json.readTree("{\"project_id\":\"p\",\"title\":\"no\"}"), token)).hasMessageContaining("未授权");
        verifyNoInteractions(tasks);
        assertThat(tools.list(grant(List.of("publish_html"), null, null)).path("tools")).isEmpty();
    }

    @Test void scopeFiltersBeforeLimitAndBlocksTaskWrites() throws Exception {
        TaskDto outside = mock(TaskDto.class), inside = mock(TaskDto.class);
        when(outside.project()).thenReturn("outside"); when(outside.status()).thenReturn("待办");
        when(inside.project()).thenReturn("allowed"); when(inside.status()).thenReturn("待办");
        when(inside.owner()).thenReturn("wd"); when(inside.title()).thenReturn("任务"); when(inside.id()).thenReturn("inside");
        when(tasks.list("wd")).thenReturn(List.of(outside, inside));
        var token = grant(List.of("list_open_tasks", "get_task", "update_task", "update_subtask_status"), List.of("allowed"), null);
        @SuppressWarnings("unchecked")
        var result = (List<WorkspaceMcpTools.TaskSummary>) tools.call("list_open_tasks", json.readTree("{\"limit\":1}"), token);
        assertThat(result).extracting(WorkspaceMcpTools.TaskSummary::id).containsExactly("inside");
        when(tasks.get("outside-task", "wd")).thenReturn(outside);
        when(tasks.lockWorkspaceTask("outside-task", "wd")).thenReturn(outside);
        for (String operation : List.of("get_task", "update_task", "update_subtask_status")) {
            assertThatThrownBy(() -> tools.call(operation, json.readTree("{\"task_id\":\"outside-task\"}"), token)).hasMessageContaining("授权范围");
        }
        verify(tasks, never()).patch(any(), any(), any());
        verify(tasks, never()).updateSubtaskStatus(any(), any(), any(), any(), any());
    }

    @Test void documentAndFolderScopeCannotBeBypassedWithDirectIds() throws Exception {
        DocDto doc = mock(DocDto.class); when(doc.kbId()).thenReturn("outside"); when(knowledge.getDoc("doc")).thenReturn(doc);
        KbDto kb = mock(KbDto.class); when(kb.id()).thenReturn("outside"); when(knowledge.getKb("outside")).thenReturn(kb);
        var token = grant(List.of("get_document", "update_document", "create_folder", "move_knowledge_node"), null, List.of("allowed"));
        for (String operation : List.of("get_document", "update_document")) {
            assertThatThrownBy(() -> tools.call(operation, json.readTree("{\"document_id\":\"doc\"}"), token)).hasMessageContaining("授权范围");
        }
        for (String operation : List.of("create_folder", "move_knowledge_node")) {
            assertThatThrownBy(() -> tools.call(operation, json.readTree("{\"kb_id\":\"outside\"}"), token)).hasMessageContaining("授权范围");
        }
        verify(knowledge, never()).updateDoc(any(), any());
        verify(knowledge, never()).createFolder(any(), any());
        verify(knowledge, never()).moveDirectoryNode(any(), any());
    }

    @Test void signingChecksAccountAccessThenPersistsSnapshotWithoutSecret() {
        var mapper = mock(WorkspaceMcpTokenMapper.class); when(mapper.selectCount(any())).thenReturn(0L);
        var service = new WorkspaceMcpTokenService(mapper, tools);
        var request = new CreateWorkspaceMcpTokenRequest("本机", null, null, List.of("get_project", "publish_html"), List.of("allowed"), null, "CLI");
        var created = service.create("wd", request);
        verify(projects).get("allowed", "wd");
        var captured = ArgumentCaptor.forClass(WorkspaceMcpTokenEntity.class); verify(mapper).insert(captured.capture());
        var row = captured.getValue();
        assertThat(row.getPermissionPolicy()).contains("allowed", "get_project").doesNotContain(created.secret());
        when(mapper.selectOne(any())).thenReturn(row);
        var token = service.authenticate(created.secret());
        assertThat(token.permits("get_project")).isTrue(); assertThat(token.permits("get_task")).isFalse();
        assertThat(token.permits("publish_html")).isTrue(); assertThat(token.canWrite()).isFalse();
        assertThat(token.permitsProject("outside")).isFalse();
        assertThat(created.token().permissionMode()).isEqualTo("granular");
        for (String corrupt : List.of("null", "{}", "{\"permissions\":[\"future_tool\"]}")) {
            row.setPermissionPolicy(corrupt); assertThat(service.authenticate(created.secret())).isNull();
        }
    }

    @Test void inaccessibleScopePreventsIssuanceEvenIfIdsAreGuessed() {
        var mapper = mock(WorkspaceMcpTokenMapper.class);
        doThrow(new NotFoundException("项目不存在")).when(projects).get("hidden", "wd");
        var service = new WorkspaceMcpTokenService(mapper, tools);
        assertThatThrownBy(() -> service.create("wd", new CreateWorkspaceMcpTokenRequest("本机", null, null,
                List.of("get_project"), List.of("hidden"), null, "MCP"))).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(mapper);
    }
}
