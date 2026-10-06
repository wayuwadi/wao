package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import cn.wayuwa.wao.service.KnowledgeService;
import cn.wayuwa.wao.service.ProjectService;
import cn.wayuwa.wao.service.TaskService;
import cn.wayuwa.wao.service.WorkspaceMcpTokenService;
import cn.wayuwa.wao.service.WorkspaceMcpTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class WorkspaceMcpControllerTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final WorkspaceMcpTokenService tokens = mock(WorkspaceMcpTokenService.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final WorkspaceMcpController controller = new WorkspaceMcpController(tokens,
            new WorkspaceMcpTools(projects, tasks, knowledge, json), json);

    @Test
    void needsDedicatedBearerEvenForToolDiscovery() throws Exception {
        JsonNode request = json.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                """);
        assertThat(controller.handle(null, request).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.handle("Bearer wao_mcp_memory", request).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(projects, never()).list(any());
    }

    @Test
    void readOnlyTokenListsOnlyReadToolsAndCannotWrite() throws Exception {
        when(tokens.authenticate("read-secret"))
                .thenReturn(new WorkspaceMcpTokenService.AuthenticatedToken("t1", "wd", false));
        JsonNode listed = controller.handle("Bearer read-secret", json.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                """)).getBody();
        assertThat(listed.path("result").path("tools").findValuesAsText("name"))
                .contains("list_open_tasks", "list_projects", "get_document")
                .doesNotContain("create_task", "update_document", "create_project");
        verify(tokens).recordVerification(new WorkspaceMcpTokenService.AuthenticatedToken("t1", "wd", false),
                listed.path("result").path("tools").size());
        JsonNode denied = controller.handle("Bearer read-secret", json.readTree("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"create_task","arguments":{}}}
                """)).getBody();
        assertThat(denied.path("result").path("isError").asBoolean()).isTrue();
        verify(tasks, never()).create(any(), any());
    }

    @Test
    void initializationAndInvalidBearerDoNotMarkVerifiedAndMetadataFailureDoesNotBreakDiscovery() throws Exception {
        var authenticated = new WorkspaceMcpTokenService.AuthenticatedToken("t", "wd", false);
        when(tokens.authenticate("valid")).thenReturn(authenticated);
        controller.handle("Bearer valid", json.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"));
        controller.handle("Bearer invalid", json.readTree("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"));
        verify(tokens, never()).recordVerification(any(), any());
        doThrow(new IllegalStateException("元数据暂不可用")).when(tokens).recordVerification(eq(authenticated), any());
        var response = controller.handle("Bearer valid", json.readTree("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}"));
        assertThat(response.getBody().path("result").path("tools").isArray()).isTrue();
    }

    @Test
    void directoryFailureRecordsFailureWithoutPretendingSuccessfulValidation() throws Exception {
        var authenticated = new WorkspaceMcpTokenService.AuthenticatedToken("t", "wd", false);
        when(tokens.authenticate("valid")).thenReturn(authenticated);
        var failingTools = mock(WorkspaceMcpTools.class);
        when(failingTools.list(authenticated)).thenThrow(new IllegalStateException("目录暂不可用"));
        var failingController = new WorkspaceMcpController(tokens, failingTools, json);
        var response = failingController.handle("Bearer valid", json.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));
        assertThat(response.getBody().path("error").path("code").asInt()).isEqualTo(-32603);
        verify(tokens).recordVerification(authenticated, null);
    }

    @Test
    void toolCallUsesAuthenticatedMemberAndReturnsRealResult() throws Exception {
        when(tokens.authenticate("write-secret"))
                .thenReturn(new WorkspaceMcpTokenService.AuthenticatedToken("t2", "wd", true));
        when(projects.list("wd")).thenReturn(List.of());
        JsonNode initialized = controller.handle("Bearer write-secret", json.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}
                """)).getBody();
        assertThat(initialized.path("result").path("serverInfo").path("name").asText())
                .isEqualTo("wao-workspace");

        JsonNode called = controller.handle("Bearer write-secret", json.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_projects","arguments":{}}}
                """)).getBody();
        verify(projects).list("wd");
        assertThat(called.path("result").path("structuredContent").path("data").isArray()).isTrue();
        assertThat(called.path("result").path("isError").asBoolean()).isFalse();
    }
}
