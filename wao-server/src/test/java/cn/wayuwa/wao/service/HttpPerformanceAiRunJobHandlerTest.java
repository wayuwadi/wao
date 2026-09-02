package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.dto.AiProjectConfigDto;
import cn.wayuwa.wao.dto.AiRunDto;
import cn.wayuwa.wao.dto.AiWorkflowDto;
import cn.wayuwa.wao.dto.HttpPerformanceInspectionConfigDto;
import cn.wayuwa.wao.dto.FunctionHealthOverviewDto;
import cn.wayuwa.wao.dto.RequestMetricEndpointDto;
import cn.wayuwa.wao.dto.RequestMetricOverviewDto;
import cn.wayuwa.wao.mapper.ProjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HttpPerformanceAiRunJobHandlerTest {

    private final HttpRequestMetricService metrics = mock(HttpRequestMetricService.class);
    private final AiRunService aiRuns = mock(AiRunService.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final HttpPerformanceInspectionConfigService config = mock(HttpPerformanceInspectionConfigService.class);
    private final FunctionHealthService functionHealth = mock(FunctionHealthService.class);
    private final FeishuTaskNotifier notifier = mock(FeishuTaskNotifier.class);
    private final HttpPerformanceAiRunJobHandler handler = new HttpPerformanceAiRunJobHandler(
            metrics, aiRuns, projects, config, functionHealth, notifier, "https://wao.wayuwa.cn/");

    private void inspectionConfig() {
        when(config.get()).thenReturn(new HttpPerformanceInspectionConfigDto(
                true, "DAILY", "06:30", 24, 5, 2, null));
    }

    @Test
    void createsRealAiRunWithTheMeasuredSnapshot() {
        inspectionConfig();
        workflow();
        when(metrics.inspectionOverview("wg", 24, 5)).thenReturn(snapshot(3968));
        AiRunDto run = mock(AiRunDto.class);
        when(run.id()).thenReturn("ar-nightly");
        when(run.taskId()).thenReturn("task-nightly");
        when(run.taskTitle()).thenReturn("HTTP 请求性能夜间巡检");
        when(aiRuns.launchInspectionGoal(eq("p-wao"), anyString(), eq("wg"), eq("https://wao.wayuwa.cn")))
                .thenReturn(run);

        JobExecutionResult execution = handler.executeWithMetadata(null, null);

        assertThat(execution.message()).isEqualTo("成功 · 已创建 AI Run ar-nightly");
        assertThat(execution.aiRunId()).isEqualTo("ar-nightly");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiRuns).launchInspectionGoal(
                eq("p-wao"), prompt.capture(), eq("wg"), eq("https://wao.wayuwa.cn"));
        assertThat(prompt.getValue())
                .contains("请求数：3968", "平均耗时：1413 ms", "P95：10005 ms")
                .contains("`/api/tasks`", "5894", "Top N 候选接口：5", "本次最多治理接口数：2")
                .contains("实际优化了哪些接口", "各自根因", "是否已经彻底解决", "后续如何规避", "经验沉淀")
                .contains("不能伪造已解决结论");
    }

    @Test
    void skipsWithoutCreatingAPlaceholderTaskWhenTheWindowIsEmpty() {
        inspectionConfig();
        workflow();
        when(metrics.inspectionOverview("wg", 24, 5)).thenReturn(snapshot(0));

        assertThat(handler.execute()).isEqualTo("跳过 · 当前没有真实 HTTP 指标或功能使用记录");

        verify(aiRuns, never()).launchInspectionGoal(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void keepsInspectionSnapshotPriorityInsteadOfResortingByAverageDuration() {
        when(config.get()).thenReturn(new HttpPerformanceInspectionConfigDto(
                true, "DAILY", "06:30", 24, 1, 1, null));
        workflow();
        List<RequestMetricEndpointDto> endpoints = List.of(
                new RequestMetricEndpointDto("GET", "/api/fast-failure", 20, 90, 120, 0, 20));
        RequestMetricOverviewDto snapshot = new RequestMetricOverviewDto(
                24, LocalDateTime.of(2026, 9, 3, 7, 30), 20, 90, 120, 0, 20, endpoints, List.of());
        when(metrics.inspectionOverview("wg", 24, 1)).thenReturn(snapshot);
        AiRunDto run = mock(AiRunDto.class);
        when(run.id()).thenReturn("ar-failures");
        when(aiRuns.launchInspectionGoal(eq("p-wao"), anyString(), eq("wg"), eq("https://wao.wayuwa.cn")))
                .thenReturn(run);

        handler.executeWithMetadata(null, null);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiRuns).launchInspectionGoal(
                eq("p-wao"), prompt.capture(), eq("wg"), eq("https://wao.wayuwa.cn"));
        assertThat(prompt.getValue()).contains("`/api/fast-failure`", "| 20 | 90 | 120 | 0 | 20 |");
    }

    @Test
    void failsClearlyWhenTheRealWorkflowIsUnavailable() {
        when(aiRuns.listWorkflows()).thenReturn(List.of());

        assertThatThrownBy(handler::execute)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未启用 AI 工作流");
        verify(metrics, never()).inspectionOverview(anyString(), eq(24), eq(5));
    }

    private void workflow() {
        AiProjectConfigDto config = new AiProjectConfigDto(
                "p-wao", true, "https://github.com/wayuwacn/wao.git", "main", "wao", "codex", null, 2, 2);
        when(aiRuns.listWorkflows()).thenReturn(List.of(new AiWorkflowDto(
                "p-wao", "Wa All in One", config, null, 0, 1, 0, null)));
        ProjectEntity project = new ProjectEntity();
        project.setId("p-wao");
        project.setOwnerId("wg");
        when(projects.selectById("p-wao")).thenReturn(project);
        when(functionHealth.overview("wg")).thenReturn(new FunctionHealthOverviewDto(
                30, LocalDateTime.of(2026, 8, 25, 1, 30), 0, List.of()));
    }

    private RequestMetricOverviewDto snapshot(int total) {
        List<RequestMetricEndpointDto> endpoints = total == 0 ? List.of() : List.of(
                new RequestMetricEndpointDto("GET", "/api/tasks", 675, 5894, 10016, 637, 280));
        return new RequestMetricOverviewDto(24, LocalDateTime.of(2026, 8, 25, 1, 30), total,
                total == 0 ? 0 : 1413, total == 0 ? 0 : 10005, total == 0 ? 0 : 1005,
                total == 0 ? 0 : 283, endpoints, List.of());
    }
}
