package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.dto.AiProjectConfigDto;
import cn.wayuwa.wao.dto.AiRunDto;
import cn.wayuwa.wao.dto.AiWorkflowDto;
import cn.wayuwa.wao.dto.ProjectDto;
import cn.wayuwa.wao.dto.RequestMetricEndpointDto;
import cn.wayuwa.wao.dto.RequestMetricOverviewDto;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HttpPerformanceAuditServiceTest {

    private final HttpRequestMetricService metrics = mock(HttpRequestMetricService.class);
    private final AiRunService runs = mock(AiRunService.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final HttpPerformanceAuditService service = new HttpPerformanceAuditService(
            metrics, runs, projects, tasks, "https://wao.example");

    @Test
    void createsOneGroundedRunUsingExistingWorkflowAndOwner() {
        when(metrics.systemOverview(24)).thenReturn(overview());
        when(runs.listWorkflows()).thenReturn(List.of(workflow()));
        ProjectDto project = mock(ProjectDto.class);
        when(project.owner()).thenReturn("wg");
        when(projects.get("p-wao")).thenReturn(project);
        AiRunDto created = mock(AiRunDto.class);
        when(created.id()).thenReturn("ar-nightly");
        when(runs.launchScheduledGoal(eq("p-wao"), anyString(), eq("wg"),
                eq("https://wao.example"), eq("runner-linux"))).thenReturn(created);

        assertThat(service.runNightlyAudit()).contains(created);

        ArgumentCaptor<String> goal = ArgumentCaptor.forClass(String.class);
        verify(runs).launchScheduledGoal(eq("p-wao"), goal.capture(), eq("wg"),
                eq("https://wao.example"), eq("runner-linux"));
        assertThat(goal.getValue())
                .contains("总请求：3968 次")
                .contains("GET /api/tasks：675 次")
                .contains("不含请求参数、正文、凭据或成员身份")
                .contains("不要为了产生提交而修改代码");
    }

    @Test
    void skipsWithoutRealMetricsOrWhenTodayAlreadyExists() {
        when(metrics.systemOverview(24)).thenReturn(emptyOverview());
        assertThat(service.runNightlyAudit()).isEmpty();
        verify(runs, never()).listWorkflows();

        when(metrics.systemOverview(24)).thenReturn(overview());
        when(runs.listWorkflows()).thenReturn(List.of(workflow()));
        when(tasks.existsByProjectAndTitle(eq("p-wao"), anyString())).thenReturn(true);
        assertThat(service.runNightlyAudit()).isEmpty();
        verify(runs, never()).launchScheduledGoal(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private static AiWorkflowDto workflow() {
        return new AiWorkflowDto("p-wao", "Wa All in One",
                new AiProjectConfigDto("p-wao", true, "https://github.com/example/wao", "main",
                        "wao", "codex", "runner-linux", 2),
                "Linux 主节点", 0, 10, 0, "2026-08-24T23:30:00", true, false);
    }

    private static RequestMetricOverviewDto overview() {
        return new RequestMetricOverviewDto(24, LocalDateTime.now(), 3_968, 1_413, 10_005,
                1_005, 283, List.of(new RequestMetricEndpointDto(
                "GET", "/api/tasks", 675, 5_894, 10_016, 637, 280)), List.of());
    }

    private static RequestMetricOverviewDto emptyOverview() {
        return new RequestMetricOverviewDto(24, LocalDateTime.now(), 0, 0, 0,
                0, 0, List.of(), List.of());
    }
}
