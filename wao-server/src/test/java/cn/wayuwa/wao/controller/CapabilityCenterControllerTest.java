package cn.wayuwa.wao.controller;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

class CapabilityCenterControllerTest {
    @Test void publicCatalogMatchesTheActualToolsAndNeverLoadsMemberData() {
        var json = new ObjectMapper();
        var projects = mock(ProjectService.class); var tasks = mock(TaskService.class); var knowledge = mock(KnowledgeService.class);
        var tools = new WorkspaceMcpTools(projects, tasks, knowledge, json);
        var members = mock(CurrentMember.class);
        var catalog = new CapabilityCenterController(tools, members, json).catalog().data().path("capabilities");
        assertThat(catalog).hasSize(tools.list(true, true).path("tools").size() + 4);
        java.util.List<String> names = new java.util.ArrayList<>();
        catalog.forEach(item -> names.add(item.path("name").asText()));
        assertThat(names).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(WorkspaceCapabilityPolicy.ALL);
        catalog.forEach(item -> {
            assertThat(item.path("cli").asText()).startsWith("wao ");
            assertThat(WorkspaceCapabilityPolicy.ALL).contains(item.path("name").asText());
        });
        assertThat(catalog.get(catalog.size() - 1).path("mcp").isNull()).isTrue();
        assertThat(catalog.toString()).contains("update_note_tags", "record_daily_note", "schedule_task");
        verifyNoInteractions(projects, tasks, knowledge, members);
    }
    @Test void browserResourceChoicesRejectAnonymousRequests() {
        var tools = mock(WorkspaceMcpTools.class);var members = mock(CurrentMember.class);
        var controller = new CapabilityCenterController(tools, members, new ObjectMapper());
        assertThatThrownBy(() -> controller.resources(mock(HttpServletRequest.class))).isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(tools);
    }
}
