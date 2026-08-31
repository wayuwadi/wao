package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.domain.ReviewableTaskRunRow;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskReviewServiceTest {
    private final TaskService tasks = mock(TaskService.class);
    private final AiTaskRunMapper runs = mock(AiTaskRunMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final TaskReviewService service = new TaskReviewService(tasks, runs, projects);

    @Test
    void derivesAcceptanceAndLatestFailureWithoutReportingAnOlderFailure() {
        TaskDto acceptance = task("t-accept", "待验收", "待验证", "P1");
        TaskDto retry = task("t-retry", "需重试", "进行中", "P0");
        TaskDto running = task("t-running", "已经重新运行", "AI 进行中", "P1");
        TaskDto completed = task("t-done", "已经验收", "已完成", "P2");
        when(tasks.list("wg")).thenReturn(List.of(acceptance, retry, running, completed));

        ProjectEntity project = new ProjectEntity();
        project.setId("p1");
        project.setName("Wa All in One");
        when(projects.selectBatchIds(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of(project));
        when(runs.selectLatestRunsForTasks(List.of("t-accept", "t-retry", "t-running", "t-done")))
                .thenReturn(List.of(
                        run("t-accept", "ar-success", "succeeded", "部署完成", LocalDateTime.of(2026, 8, 30, 12, 0)),
                        run("t-retry", "ar-failed", "failed", "编译失败\n完整堆栈", LocalDateTime.of(2026, 8, 30, 13, 0)),
                        run("t-running", "ar-running", "running", null, null),
                        run("t-done", "ar-old-failed", "failed", "旧失败", LocalDateTime.of(2026, 8, 29, 13, 0))));

        var result = service.list("wg");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).taskId()).isEqualTo("t-retry");
        assertThat(result.get(0).action()).isEqualTo("RETRY");
        assertThat(result.get(0).resultPreview()).isEqualTo("编译失败");
        assertThat(result.get(1).taskId()).isEqualTo("t-accept");
        assertThat(result.get(1).action()).isEqualTo("ACCEPTANCE");
        assertThat(result.get(1).projectName()).isEqualTo("Wa All in One");
    }

    @Test
    void includesManuallyMarkedAcceptanceWithoutInventingRunData() {
        when(tasks.list("wg")).thenReturn(List.of(task("t-manual", "人工结果", "待验证", "P2")));
        when(runs.selectLatestRunsForTasks(List.of("t-manual"))).thenReturn(List.of());
        ProjectEntity project = new ProjectEntity();
        project.setId("p1");
        project.setName("Wa All in One");
        when(projects.selectBatchIds(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of(project));

        assertThat(service.list("wg")).singleElement().satisfies(item -> {
            assertThat(item.action()).isEqualTo("ACCEPTANCE");
            assertThat(item.runId()).isNull();
            assertThat(item.resultPreview()).isNull();
        });
    }

    private static TaskDto task(String id, String title, String status, String priority) {
        return new TaskDto(id, title, "p1", status, priority, "wg", List.of(), null, null, null, "",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static ReviewableTaskRunRow run(
            String taskId, String runId, String status, String summary, LocalDateTime finishedAt) {
        ReviewableTaskRunRow row = new ReviewableTaskRunRow();
        row.setReviewTaskId(taskId);
        row.setRunId(runId);
        row.setStatus(status);
        row.setResultSummary(summary);
        row.setFinishedAt(finishedAt);
        return row;
    }
}
