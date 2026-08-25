package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.AiProjectConfigEntity;
import cn.wayuwa.wao.domain.AiRunProgressEntity;
import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import cn.wayuwa.wao.domain.AiRunnerPairCodeEntity;
import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.domain.AiTaskRunTaskEntity;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.AiRunDto;
import cn.wayuwa.wao.dto.AiRunnerClaimDto;
import cn.wayuwa.wao.dto.AiRunnerDto;
import cn.wayuwa.wao.dto.CompleteAiRunRequest;
import cn.wayuwa.wao.dto.CreateTaskRequest;
import cn.wayuwa.wao.dto.HostedPageDto;
import cn.wayuwa.wao.dto.ImageItemDto;
import cn.wayuwa.wao.dto.PairedAiRunnerDto;
import cn.wayuwa.wao.dto.PairAiRunnerRequest;
import cn.wayuwa.wao.dto.ProjectMemoryCandidate;
import cn.wayuwa.wao.dto.SaveAiProjectConfigRequest;
import cn.wayuwa.wao.dto.ReportAiRunProgressRequest;
import cn.wayuwa.wao.dto.RunnerCodexQuotaReport;
import cn.wayuwa.wao.mapper.AiProjectConfigMapper;
import cn.wayuwa.wao.mapper.AiRunProgressMapper;
import cn.wayuwa.wao.mapper.AiRunnerNodeMapper;
import cn.wayuwa.wao.mapper.AiRunnerPairCodeMapper;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class AiRunServiceTest {

    private final AiProjectConfigMapper configMapper = mock(AiProjectConfigMapper.class);
    private final AiRunnerNodeMapper runnerMapper = mock(AiRunnerNodeMapper.class);
    private final AiRunnerPairCodeMapper pairMapper = mock(AiRunnerPairCodeMapper.class);
    private final AiTaskRunMapper runMapper = mock(AiTaskRunMapper.class);
    private final AiRunProgressMapper progressMapper = mock(AiRunProgressMapper.class);
    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final TaskService tasks = mock(TaskService.class);
    private final FeishuTaskNotifier notifier = mock(FeishuTaskNotifier.class);
    private final RunnerScriptService scripts = mock(RunnerScriptService.class);
    private final AiAccountService accounts = mock(AiAccountService.class);
    private final HostedPageService hostedPages = mock(HostedPageService.class);
    private final ImageService images = mock(ImageService.class);
    private final CodexQuotaService codexQuotas = mock(CodexQuotaService.class);
    private final ProjectMemoryService projectMemories = mock(ProjectMemoryService.class);
    private final AiRunService service = new AiRunService(
            configMapper, runnerMapper, pairMapper, runMapper, progressMapper, taskMapper, projectMapper,
            tasks, notifier, scripts, accounts, hostedPages, images, codexQuotas, projectMemories);

    @BeforeEach
    void emptyProgressByDefault() {
        when(progressMapper.selectList(any())).thenReturn(List.of());
        when(hostedPages.runReportUrls(any())).thenReturn(Map.of());
        when(projectMemories.recall(any(), any())).thenReturn(List.of());
        when(projectMemories.readReferences(any())).thenReturn(Map.of());
        when(projectMemories.writeReferences(any())).thenReturn(Map.of());
    }

    @Test
    void runningNodeCanPublishPersistentReport() {
        runningRun();
        var html = new MockMultipartFile("html", "report.html", "text/html", "<html>报告</html>".getBytes());
        when(hostedPages.saveRunReport(eq("ar1"), eq(html), eq("记录用量 · AI 报告"), isNull()))
                .thenReturn(new HostedPageDto("run-report-ar1", "ai-run-ar1", "记录用量 · AI 报告",
                        "report.html", false, "8月11日 19:00", "/api/pages/ai-run-ar1"));

        var report = service.saveReport("local-token", "ar1", html);

        assertThat(report.url()).isEqualTo("/api/pages/ai-run-ar1");
        verify(hostedPages).saveRunReport("ar1", html, "记录用量 · AI 报告", null);
        verify(progressMapper).insert(any(AiRunProgressEntity.class));
    }

    @Test
    void enabledProjectRequiresRepository() {
        when(projectMapper.selectById("p1")).thenReturn(project("p1", "WAO"));

        assertThatThrownBy(() -> service.saveConfig("p1",
                new SaveAiProjectConfigRequest(true, "", "main", "wao", "codex", null, 2)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("仓库");
    }

    @Test
    void savesSafeProjectConfiguration() {
        when(projectMapper.selectById("p1")).thenReturn(project("p1", "WAO"));
        when(configMapper.selectById("p1")).thenReturn(null);

        var result = service.saveConfig("p1",
                new SaveAiProjectConfigRequest(true, "https://github.com/acme/wao.git", "main", "wao", "claude", null, 3));

        assertThat(result.enabled()).isTrue();
        assertThat(result.maxParallelRuns()).isEqualTo(3);
        verify(configMapper).insert(any(AiProjectConfigEntity.class));
    }

    @Test
    void workflowOverviewExposesOrphanedConfigurationForCleanup() {
        AiProjectConfigEntity current = config("p1");
        AiProjectConfigEntity orphan = config("p-deleted");
        when(configMapper.selectList(null)).thenReturn(List.of(current, orphan));
        when(runnerMapper.selectList(null)).thenReturn(List.of());
        when(runMapper.selectList(any())).thenReturn(List.of());
        when(projectMapper.selectList(null)).thenReturn(List.of(project("p1", "WAO")));

        var workflows = service.listWorkflows();

        assertThat(workflows).hasSize(2);
        assertThat(workflows).anySatisfy(workflow -> {
            assertThat(workflow.projectId()).isEqualTo("p-deleted");
            assertThat(workflow.projectName()).contains("已删除项目");
            assertThat(workflow.configured()).isTrue();
            assertThat(workflow.projectDeleted()).isTrue();
        });
    }

    @Test
    void deletesIdleWorkflowButKeepsHistoricalRunsOutsideConfiguration() {
        when(configMapper.selectById("p1")).thenReturn(config("p1"));
        when(runMapper.selectCount(any())).thenReturn(0L);

        service.deleteWorkflow("p1");

        verify(configMapper).deleteById("p1");
    }

    @Test
    void refusesToDeleteWorkflowWithActiveRuns() {
        when(configMapper.selectById("p1")).thenReturn(config("p1"));
        when(runMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.deleteWorkflow("p1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("2 个运行");
        verify(configMapper, never()).deleteById("p1");
    }

    @Test
    void pairCodeCanOnlyBeExchangedForLocalToken() {
        AiRunnerPairCodeEntity code = new AiRunnerPairCodeEntity();
        code.setId("pc1");
        code.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(pairMapper.selectOne(any())).thenReturn(code);

        PairedAiRunnerDto result = service.pair(new PairAiRunnerRequest("ABCD2345", "娃哥电脑", "Windows", List.of("codex", "claude"), 2));

        assertThat(result.runnerId()).startsWith("rn");
        assertThat(result.token()).hasSize(48);
        assertThat(code.getUsedAt()).isNotNull();
        verify(runnerMapper).insert(any(AiRunnerNodeEntity.class));
        verify(pairMapper).updateById(code);
    }

    @Test
    void enqueueCreatesRunAndMarksTaskAsAiRunning() {
        TaskEntity task = task("t1", "p1", "修复登录");
        AiProjectConfigEntity config = config("p1");
        when(taskMapper.selectById("t1")).thenReturn(task);
        when(configMapper.selectById("p1")).thenReturn(config);
        when(runnerMapper.selectList(any())).thenReturn(List.of(runner("rn1", "娃哥电脑", "codex")));
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));

        var result = service.enqueue(List.of("t1"), null, null, null, null, "wg", "https://wao.example");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).taskTitle()).isEqualTo("修复登录");
        assertThat(result.get(0).engine()).isEqualTo("codex");
        assertThat(task.getStatus()).isEqualTo("AI 进行中");
        ArgumentCaptor<AiTaskRunEntity> run = ArgumentCaptor.forClass(AiTaskRunEntity.class);
        verify(runMapper).insert(run.capture());
        // 主任务派工一律只带未完成、且没被单独派过工的子任务，避免同一条被两台电脑各做一遍
        assertThat(run.getValue().getPromptUrl())
                .isEqualTo("https://wao.example/api/share/tasks/t1.md?openOnly=true");
        assertThat(run.getValue().getSubId()).isNull();
        assertThat(run.getValue().getRequestedModel()).isEqualTo("gpt-5.6-sol");
        assertThat(run.getValue().getReasoningEffort()).isEqualTo("medium");
        assertThat(result.get(0).serviceTier()).isEqualTo("default");
    }

    @Test
    void enqueueMergesSelectedTasksIntoOneRunAndOneOrderedContext() {
        TaskEntity first = task("t1", "p1", "整理机器人点子");
        TaskEntity second = task("t2", "p1", "补齐批量验收");
        when(taskMapper.selectById("t1")).thenReturn(first);
        when(taskMapper.selectById("t2")).thenReturn(second);
        when(configMapper.selectById("p1")).thenReturn(config("p1"));
        when(runnerMapper.selectList(any())).thenReturn(List.of(runner("rn1", "娃哥电脑", "codex")));
        when(runMapper.countActiveMainRunsForTask(any())).thenReturn(0L);
        when(runMapper.selectTaskLinks(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            String runId = ((List<String>) invocation.getArgument(0)).get(0);
            return List.of(runTask(runId, "t1", 0), runTask(runId, "t2", 1));
        });
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(first, second));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));

        var result = service.enqueue(List.of("t1", "t2"), null, null, null, null,
                null, null, false, true, "wg", "https://wao.example");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).taskIds()).containsExactly("t1", "t2");
        assertThat(result.get(0).taskTitle()).startsWith("2 个任务");
        assertThat(first.getStatus()).isEqualTo("AI 进行中");
        assertThat(second.getStatus()).isEqualTo("AI 进行中");
        ArgumentCaptor<AiTaskRunEntity> run = ArgumentCaptor.forClass(AiTaskRunEntity.class);
        verify(runMapper).insert(run.capture());
        assertThat(run.getValue().getPromptUrl())
                .isEqualTo("https://wao.example/api/share/ai-runs/" + run.getValue().getId() + ".md?openOnly=true");
        verify(runMapper).insertTaskLink(run.getValue().getId(), "t1", 0);
        verify(runMapper).insertTaskLink(run.getValue().getId(), "t2", 1);
    }

    @Test
    void mergedEnqueueRejectsDifferentProjectsBeforeCreatingARun() {
        when(taskMapper.selectById("t1")).thenReturn(task("t1", "p1", "项目一任务"));
        when(taskMapper.selectById("t2")).thenReturn(task("t2", "p2", "项目二任务"));

        assertThatThrownBy(() -> service.enqueue(List.of("t1", "t2"), null, null, null, null,
                null, null, false, true, "wg", "https://wao.example"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("同一项目");
        verify(runMapper, never()).insert(any(AiTaskRunEntity.class));
    }

    @Test
    void enqueuePersistsSelectedCodexModelAndHighReasoning() {
        TaskEntity task = task("t1", "p1", "复杂开发任务");
        when(taskMapper.selectById("t1")).thenReturn(task);
        when(configMapper.selectById("p1")).thenReturn(config("p1"));
        when(runnerMapper.selectList(any())).thenReturn(List.of(runner("rn1", "Linux 主节点", "codex")));
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));

        service.enqueue(List.of("t1"), null, "codex", null, null,
                "gpt-5.6-sol", "high", false, "wg", "https://wao.example");

        ArgumentCaptor<AiTaskRunEntity> run = ArgumentCaptor.forClass(AiTaskRunEntity.class);
        verify(runMapper).insert(run.capture());
        assertThat(run.getValue().getRequestedModel()).isEqualTo("gpt-5.6-sol");
        assertThat(run.getValue().getReasoningEffort()).isEqualTo("high");
        assertThat(run.getValue().getPlanMode()).isFalse();
    }

    @Test
    void enqueuePersistsSelectedCodexPlanMode() {
        TaskEntity task = task("t1", "p1", "先规划再实现");
        when(taskMapper.selectById("t1")).thenReturn(task);
        when(configMapper.selectById("p1")).thenReturn(config("p1"));
        when(runnerMapper.selectList(any())).thenReturn(List.of(runner("rn1", "Linux 主节点", "codex")));
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));

        service.enqueue(List.of("t1"), null, "codex", null, null,
                "gpt-5.6-sol", "medium", true, "wg", "https://wao.example");

        ArgumentCaptor<AiTaskRunEntity> run = ArgumentCaptor.forClass(AiTaskRunEntity.class);
        verify(runMapper).insert(run.capture());
        assertThat(run.getValue().getPlanMode()).isTrue();
    }

    @Test
    void enqueueRejectsUnknownCodexModelBeforeCreatingRun() {
        when(taskMapper.selectById("t1")).thenReturn(task("t1", "p1", "错误模型"));
        when(configMapper.selectById("p1")).thenReturn(config("p1"));

        assertThatThrownBy(() -> service.enqueue(List.of("t1"), null, "codex", null, null,
                "invented-model", "medium", false, "wg", "https://wao.example"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("不支持的 Codex 模型");
        verify(runMapper, never()).insert(any(AiTaskRunEntity.class));
    }

    @Test
    void autonomousGoalCreatesOneTaskWithExecutionContractAndImmediatelyEnqueuesIt() {
        TaskEntity task = task("t1", "p1", "把黄帝内经和易经结合 AI");
        when(tasks.create(any(CreateTaskRequest.class), eq("wg"))).thenReturn(taskDto(null, null));
        when(taskMapper.selectById("t1")).thenReturn(task);
        when(configMapper.selectById("p1")).thenReturn(config("p1"));
        when(runnerMapper.selectList(any())).thenReturn(List.of(runner("rn1", "Linux 主节点", "codex")));
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));

        String longContext = "详细背景".repeat(1_100);
        String goal = "## 把黄帝内经和易经结合 AI\n做成每日一卦产品\n" + longContext;

        AiRunDto result = service.launchGoal("p1", goal, "wg", "https://wao.example");

        assertThat(result.taskId()).isEqualTo("t1");
        assertThat(goal.length()).isGreaterThan(4_000);
        ArgumentCaptor<CreateTaskRequest> request = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(tasks).create(request.capture(), eq("wg"));
        assertThat(request.getValue().title()).isEqualTo("把黄帝内经和易经结合 AI");
        assertThat(request.getValue().desc())
                .contains("做成每日一卦产品")
                .contains("自主执行约定")
                .contains("主动盘点")
                .contains("自己拆解")
                .contains(longContext);
        verify(runMapper).insert(any(AiTaskRunEntity.class));
    }

    @Test
    void autonomousGoalCanTargetTheComputerSelectedForThisLaunch() {
        TaskEntity task = task("t1", "p1", "在指定电脑执行");
        when(tasks.create(any(CreateTaskRequest.class), eq("wg"))).thenReturn(taskDto(null, null));
        when(taskMapper.selectById("t1")).thenReturn(task);
        when(configMapper.selectById("p1")).thenReturn(config("p1"));
        when(runnerMapper.selectById("rn-selected"))
                .thenReturn(runner("rn-selected", "选中的 Windows 电脑", "codex"));
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));

        AiRunDto result = service.launchGoal("p1", "在指定电脑执行", "wg", "https://wao.example",
                "rn-selected", null);

        assertThat(result.requestedRunnerId()).isEqualTo("rn-selected");
        ArgumentCaptor<AiTaskRunEntity> run = ArgumentCaptor.forClass(AiTaskRunEntity.class);
        verify(runMapper).insert(run.capture());
        assertThat(run.getValue().getRequestedRunnerId()).isEqualTo("rn-selected");
    }

    @Test
    void autonomousGoalPersistsTheSelectedCodexPlanMode() {
        TaskEntity task = task("t1", "p1", "先规划再执行");
        when(tasks.create(any(CreateTaskRequest.class), eq("wg"))).thenReturn(taskDto(null, null));
        when(taskMapper.selectById("t1")).thenReturn(task);
        when(configMapper.selectById("p1")).thenReturn(config("p1"));
        when(runnerMapper.selectList(any())).thenReturn(List.of(runner("rn1", "Linux 主节点", "codex")));
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));

        AiRunDto result = service.launchGoal("p1", "先规划再执行", "wg", "https://wao.example",
                null, null, true);

        assertThat(result.planMode()).isTrue();
        ArgumentCaptor<AiTaskRunEntity> run = ArgumentCaptor.forClass(AiTaskRunEntity.class);
        verify(runMapper).insert(run.capture());
        assertThat(run.getValue().getPlanMode()).isTrue();
    }

    @Test
    void autonomousGoalPersistsInlineImageAndUsesReadableTextAsTitle() {
        TaskEntity task = task("t1", "p1", "根据截图修复展示");
        when(images.store(any(byte[].class), eq("image/png"), eq("image.png"), eq("自主目标"), eq(true)))
                .thenReturn(new ImageItemDto("img1", "image.png", "5 B", "刚刚", "自主目标",
                        "/api/images/img1/raw", "#eee", "#ddd"));
        when(tasks.create(any(CreateTaskRequest.class), eq("wg"))).thenReturn(taskDto(null, null));
        when(taskMapper.selectById("t1")).thenReturn(task);
        when(configMapper.selectById("p1")).thenReturn(config("p1"));
        when(runnerMapper.selectList(any())).thenReturn(List.of(runner("rn1", "Linux 主节点", "codex")));
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));

        service.launchGoal("p1",
                "![image.png](data:image/png;base64,aGVsbG8=)\n\n根据截图修复展示", "wg", "https://wao.example");

        ArgumentCaptor<CreateTaskRequest> request = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(tasks).create(request.capture(), eq("wg"));
        assertThat(request.getValue().title()).isEqualTo("根据截图修复展示");
        assertThat(request.getValue().desc())
                .contains("![image.png](/api/images/img1/raw)")
                .doesNotContain("data:image");
        verify(images).store(any(byte[].class), eq("image/png"), eq("image.png"), eq("自主目标"), eq(true));
    }

    @Test
    void legacyInlineImageGetsReadableTitleAndSmallProxyPreview() {
        AiTaskRunEntity run = new AiTaskRunEntity();
        run.setId("ar-old");
        run.setTaskId("t-old");
        run.setProjectId("p1");
        run.setStatus("queued");
        TaskEntity task = task("t-old", "p1", "![image.png](data:image/png;base64,aGVsbG8=)…");
        task.setDescription("## 目标\n\n![image.png](data:image/png;base64,aGVsbG8=)\n\n根据截图修复展示"
                + "\n\n## 自主执行约定\n\n- 自行推进");
        when(runMapper.selectList(any())).thenReturn(List.of(), List.of(run));
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));
        when(taskMapper.selectById("t-old")).thenReturn(task);
        when(runMapper.selectById("ar-old")).thenReturn(run);

        var runs = service.listRuns(false);

        assertThat(runs.get(0).taskTitle()).isEqualTo("根据截图修复展示");
        assertThat(runs.get(0).previewImages()).containsExactly("/api/ai/runs/ar-old/images/0");
        assertThat(runs.get(0).toString()).doesNotContain("aGVsbG8=");
        assertThat(service.runPreviewImage("ar-old", 0).content()).isEqualTo("hello".getBytes());
        assertThat(service.runPreviewImage("ar-old", 0).contentType()).isEqualTo("image/png");
    }

    @Test
    void finishedRunHistoryIsPaginatedWithoutTheHomepageLimit() {
        AiTaskRunEntity run = new AiTaskRunEntity();
        run.setId("ar-history");
        run.setTaskId("t1");
        run.setProjectId("p1");
        run.setStatus("succeeded");
        when(runMapper.selectList(any())).thenReturn(List.of());
        when(runMapper.countFinishedRuns()).thenReturn(61L);
        when(runMapper.selectFinishedRunPage(20L, 20)).thenReturn(List.of(run));
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task("t1", "p1", "历史运行")));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));

        var history = service.listRunHistory(2, 20);

        assertThat(history.total()).isEqualTo(61);
        assertThat(history.page()).isEqualTo(2);
        assertThat(history.items()).extracting(AiRunDto::taskTitle).containsExactly("历史运行");
        verify(runMapper).selectFinishedRunPage(20L, 20);
    }

    @Test
    void quotaDrivenGoalPinsTheAccountThatPassedTheThreshold() {
        TaskEntity task = task("t1", "p1", "自动优化体验");
        when(tasks.create(any(CreateTaskRequest.class), eq("system"))).thenReturn(taskDto(null, null));
        when(taskMapper.selectById("t1")).thenReturn(task);
        when(configMapper.selectById("p1")).thenReturn(config("p1"));
        AiRunnerNodeEntity linux = runner("rn-linux", "Linux 主节点", "codex");
        linux.setLastSeenAt(LocalDateTime.now());
        when(runnerMapper.selectList(any())).thenReturn(List.of(linux));
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));

        service.launchGoal("p1", "自动优化一项真实体验", "system", "https://wao.example", "aa-quota");

        verify(accounts).validateRunAccount("aa-quota", "codex");
        ArgumentCaptor<AiTaskRunEntity> run = ArgumentCaptor.forClass(AiTaskRunEntity.class);
        verify(runMapper).insert(run.capture());
        assertThat(run.getValue().getAiAccountId()).isEqualTo("aa-quota");
        assertThat(run.getValue().getRequestedRunnerId()).isEqualTo("rn-linux");
    }

    @Test
    void enqueueCanTargetOneComputerAndOneAiTool() {
        TaskEntity task = task("t1", "p1", "接入 Claude");
        when(taskMapper.selectById("t1")).thenReturn(task);
        when(configMapper.selectById("p1")).thenReturn(config("p1"));
        when(runnerMapper.selectById("rn2")).thenReturn(runner("rn2", "Linux 主节点", "claude"));
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));

        var result = service.enqueue(List.of("t1"), null, "claude", "rn2", null, "wg", "https://wao.example");

        assertThat(result.get(0).engine()).isEqualTo("claude");
        assertThat(result.get(0).requestedRunnerId()).isEqualTo("rn2");
        ArgumentCaptor<AiTaskRunEntity> run = ArgumentCaptor.forClass(AiTaskRunEntity.class);
        verify(runMapper).insert(run.capture());
        assertThat(run.getValue().getEngine()).isEqualTo("claude");
        assertThat(run.getValue().getRequestedRunnerId()).isEqualTo("rn2");
    }

    @Test
    void enqueuePinsSelectedAccountToTheRun() {
        TaskEntity task = task("t1", "p1", "使用指定 Codex 账号");
        AiRunnerNodeEntity windows = runner("rn-win", "娃哥Windows电脑", "codex");
        AiRunnerNodeEntity linux = runner("rn-linux", "Linux 主节点", "codex");
        linux.setLastSeenAt(LocalDateTime.now());
        when(taskMapper.selectById("t1")).thenReturn(task);
        when(configMapper.selectById("p1")).thenReturn(config("p1"));
        when(runnerMapper.selectList(any())).thenReturn(List.of(windows, linux));
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));

        service.enqueue(List.of("t1"), null, "codex", null, "aa-1", "wg", "https://wao.example");

        verify(accounts).validateRunAccount("aa-1", "codex");
        verify(accounts, never()).runAccount(any(), any());
        ArgumentCaptor<AiTaskRunEntity> run = ArgumentCaptor.forClass(AiTaskRunEntity.class);
        verify(runMapper).insert(run.capture());
        assertThat(run.getValue().getAiAccountId()).isEqualTo("aa-1");
        assertThat(run.getValue().getRequestedRunnerId()).isEqualTo("rn-linux");
    }

    @Test
    void customApiAccountKeepsItsOwnModelConfiguration() {
        TaskEntity task = task("t1", "p1", "使用自定义模型");
        when(taskMapper.selectById("t1")).thenReturn(task);
        when(configMapper.selectById("p1")).thenReturn(config("p1"));
        when(runnerMapper.selectById("rn1")).thenReturn(runner("rn1", "Linux 主节点", "codex"));
        when(accounts.usesCustomApi("aa-custom", "codex")).thenReturn(true);
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));

        service.enqueue(List.of("t1"), null, "codex", "rn1", "aa-custom",
                "gpt-5.6-sol", "high", false, "wg", "https://wao.example");

        ArgumentCaptor<AiTaskRunEntity> run = ArgumentCaptor.forClass(AiTaskRunEntity.class);
        verify(runMapper).insert(run.capture());
        assertThat(run.getValue().getRequestedModel()).isNull();
        assertThat(run.getValue().getReasoningEffort()).isNull();
    }

    @Test
    void enqueueWithCentralAccountRequiresLinuxRunnerUnlessComputerIsExplicit() {
        TaskEntity task = task("t1", "p1", "使用集中账号");
        when(taskMapper.selectById("t1")).thenReturn(task);
        when(configMapper.selectById("p1")).thenReturn(config("p1"));
        when(runnerMapper.selectList(any())).thenReturn(List.of(runner("rn-win", "娃哥Windows电脑", "codex")));

        assertThatThrownBy(() -> service.enqueue(List.of("t1"), null, "codex", null,
                "aa-1", "wg", "https://wao.example"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Linux 主节点尚未接入 Codex");
        verify(runMapper, never()).insert(any(AiTaskRunEntity.class));
    }

    @Test
    void enqueueRejectsComputerThatDoesNotHaveTheRequestedAiTool() {
        when(taskMapper.selectById("t1")).thenReturn(task("t1", "p1", "接入 Claude"));
        when(configMapper.selectById("p1")).thenReturn(config("p1"));
        when(runnerMapper.selectById("rn1")).thenReturn(runner("rn1", "娃哥电脑", "codex"));

        assertThatThrownBy(() -> service.enqueue(List.of("t1"), null, "claude", "rn1", null, "wg", "https://wao.example"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Claude Code");
        verify(runMapper, never()).insert(any(AiTaskRunEntity.class));
    }

    @Test
    void onlineRunnerClaimsQueuedTaskWithProjectLimits() {
        AiRunnerNodeEntity runner = new AiRunnerNodeEntity();
        runner.setId("rn1");
        runner.setName("娃弟电脑");
        runner.setMaxConcurrency(2);
        runner.setLastSeenAt(LocalDateTime.now());
        AiTaskRunEntity queued = new AiTaskRunEntity();
        queued.setId("ar1");
        queued.setTaskId("t1");
        queued.setProjectId("p1");
        queued.setStatus("queued");
        queued.setRequestedModel("gpt-5.6-sol");
        queued.setReasoningEffort("high");
        queued.setPlanMode(true);
        queued.setPromptUrl("https://wao.example/api/share/tasks/t1.md");
        queued.setBranchName("ai/wao-ar1");
        when(runnerMapper.selectOne(any())).thenReturn(runner);
        when(runMapper.selectList(any())).thenReturn(List.of(), List.of(queued));
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(configMapper.selectById("p1")).thenReturn(config("p1"));
        when(taskMapper.selectById("t1")).thenReturn(task("t1", "p1", "完成自动化"));
        when(projectMapper.selectById("p1")).thenReturn(project("p1", "WAO"));

        AiRunnerClaimDto result = service.claim("local-token");

        assertThat(result.runId()).isEqualTo("ar1");
        assertThat(result.repoUrl()).isEqualTo("https://github.com/acme/wao.git");
        assertThat(result.model()).isEqualTo("gpt-5.6-sol");
        assertThat(result.reasoningEffort()).isEqualTo("high");
        assertThat(result.serviceTier()).isEqualTo("default");
        assertThat(result.planMode()).isTrue();
        assertThat(queued.getStatus()).isEqualTo("running");
        assertThat(queued.getRunnerId()).isEqualTo("rn1");
        verify(runMapper).updateById(eq(queued));
    }

    @Test
    void pauseCancelsActiveRunAndReturnsTaskToInProgress() {
        AiTaskRunEntity run = new AiTaskRunEntity();
        run.setId("ar1");
        run.setTaskId("t1");
        run.setProjectId("p1");
        run.setStatus("running");
        when(runMapper.selectById("ar1")).thenReturn(run);
        when(taskMapper.selectById("t1")).thenReturn(task("t1", "p1", "暂停任务"));
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task("t1", "p1", "暂停任务")));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));

        AiRunDto result = service.pause("ar1");

        assertThat(result.status()).isEqualTo("cancelled");
        assertThat(run.getFinishedAt()).isNotNull();
        verify(runMapper, times(2)).updateById(run);
        verify(taskMapper, times(1)).updateById(any(TaskEntity.class));
    }

    @Test
    void runnerOnlyClaimsRunsForAiToolsItHasInstalled() {
        AiRunnerNodeEntity runner = runner("rn1", "娃哥电脑", "codex");
        runner.setMaxConcurrency(2);
        runner.setLastSeenAt(LocalDateTime.now());
        AiTaskRunEntity claudeRun = new AiTaskRunEntity();
        claudeRun.setId("ar1");
        claudeRun.setTaskId("t1");
        claudeRun.setProjectId("p1");
        claudeRun.setEngine("claude");
        claudeRun.setStatus("queued");
        when(runnerMapper.selectOne(any())).thenReturn(runner);
        when(runMapper.selectList(any())).thenReturn(List.of(), List.of(claudeRun));
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(configMapper.selectById("p1")).thenReturn(config("p1"));

        assertThat(service.claim("local-token")).isNull();
        assertThat(claudeRun.getStatus()).isEqualTo("queued");
    }

    @Test
    void runnerOnlyClaimsRunsPinnedToItself() {
        AiRunnerNodeEntity runner = runner("rn1", "娃哥电脑", "codex", "claude");
        runner.setMaxConcurrency(2);
        runner.setLastSeenAt(LocalDateTime.now());
        AiTaskRunEntity pinned = new AiTaskRunEntity();
        pinned.setId("ar1");
        pinned.setTaskId("t1");
        pinned.setProjectId("p1");
        pinned.setEngine("claude");
        pinned.setStatus("queued");
        pinned.setRequestedRunnerId("rn2");
        when(runnerMapper.selectOne(any())).thenReturn(runner);
        when(runMapper.selectList(any())).thenReturn(List.of(), List.of(pinned));
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(configMapper.selectById("p1")).thenReturn(config("p1"));

        assertThat(service.claim("local-token")).isNull();
        assertThat(pinned.getStatus()).isEqualTo("queued");
    }

    @Test
    void adminMaxConcurrencyOverridesRunnerValueInList() {
        AiRunnerNodeEntity runner = runner("rn1", "娃哥电脑", "codex");
        runner.setMaxConcurrency(2);
        runner.setAdminMaxConcurrency(4);
        when(runnerMapper.selectList(any())).thenReturn(List.of(runner));
        when(runMapper.selectList(any())).thenReturn(List.of());

        var result = service.listRunners();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).maxConcurrency()).isEqualTo(4);
    }

    @Test
    void listRunnersFallsBackToRunnerValueWhenAdminNotSet() {
        AiRunnerNodeEntity runner = runner("rn1", "娃哥电脑", "codex");
        runner.setMaxConcurrency(3);
        when(runnerMapper.selectList(any())).thenReturn(List.of(runner));
        when(runMapper.selectList(any())).thenReturn(List.of());

        var result = service.listRunners();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).maxConcurrency()).isEqualTo(3);
    }

    @Test
    void heartbeatDoesNotOverrideAdminMaxConcurrency() {
        AiRunnerNodeEntity runner = runner("rn1", "娃哥电脑", "codex");
        runner.setMaxConcurrency(2);
        runner.setAdminMaxConcurrency(5);
        runner.setTokenHash("hashed-token");
        runner.setLastSeenAt(LocalDateTime.now().minusMinutes(1));
        when(runnerMapper.selectOne(any())).thenReturn(runner);
        when(runnerMapper.selectList(any())).thenReturn(List.of(runner));
        when(runMapper.selectList(any())).thenReturn(List.of());

        var result = service.heartbeat(
                "local-token", 3, null, null, "30-56-0f-09-61-11", "203.0.113.8", null, null, null);

        assertThat(runner.getAdminMaxConcurrency()).isEqualTo(5);
        assertThat(runner.getMaxConcurrency()).isEqualTo(3);
        assertThat(result.maxConcurrency()).isEqualTo(5);
        assertThat(runner.getWakeMac()).isEqualTo("30:56:0F:09:61:11");
        assertThat(runner.getWakeHost()).isEqualTo("203.0.113.8");
        assertThat(runner.getWakePort()).isEqualTo(9);
    }

    @Test
    void heartbeatStoresScriptHashAndClearsUpdateFlagWhenAlreadyLatest() {
        AiRunnerNodeEntity runner = runner("rn1", "娃哥电脑", "codex");
        runner.setTokenHash("hashed-token");
        runner.setUpdateRequestedAt(LocalDateTime.now().minusMinutes(5));
        when(runnerMapper.selectOne(any())).thenReturn(runner);
        when(runnerMapper.selectList(any())).thenReturn(List.of(runner));
        when(runMapper.selectList(any())).thenReturn(List.of());
        when(scripts.available()).thenReturn(true);
        when(scripts.hash()).thenReturn("latest-hash");

        service.heartbeat("local-token", null, null, "latest-hash", null, null, null, null, null);

        assertThat(runner.getScriptHash()).isEqualTo("latest-hash");
        assertThat(runner.getUpdateRequestedAt()).isNull();
        verify(runnerMapper).update(eq(runner), any());
        verify(runnerMapper, never()).updateById(runner);
    }

    @Test
    void heartbeatKeepsUpdateFlagWhileRunnerStillReportsOldScript() {
        AiRunnerNodeEntity runner = runner("rn1", "娃哥电脑", "codex");
        runner.setTokenHash("hashed-token");
        runner.setUpdateRequestedAt(LocalDateTime.now().minusMinutes(5));
        when(runnerMapper.selectOne(any())).thenReturn(runner);
        when(runnerMapper.selectList(any())).thenReturn(List.of(runner));
        when(runMapper.selectList(any())).thenReturn(List.of());
        when(scripts.available()).thenReturn(true);
        when(scripts.hash()).thenReturn("latest-hash");

        AiRunnerDto dto = service.heartbeat("local-token", null, null, "old-hash", null, null, null, null, null);

        assertThat(runner.getUpdateRequestedAt()).isNotNull();
        assertThat(dto.updateRequested()).isTrue();
        assertThat(dto.scriptUpToDate()).isFalse();
        verify(runnerMapper).updateById(runner);
        verify(runnerMapper, never()).update(any(), any());
    }

    @Test
    void heartbeatAutomaticallyRequestsUpdateForAnOutdatedRunner() {
        AiRunnerNodeEntity runner = runner("rn1", "娃哥电脑", "codex");
        runner.setTokenHash("hashed-token");
        when(runnerMapper.selectOne(any())).thenReturn(runner);
        when(runnerMapper.selectList(any())).thenReturn(List.of(runner));
        when(runMapper.selectList(any())).thenReturn(List.of());
        when(scripts.available()).thenReturn(true);
        when(scripts.hash()).thenReturn("latest-hash");

        AiRunnerDto dto = service.heartbeat(
                "local-token", null, null, "old-hash", null, null, null, null, null);

        assertThat(runner.getUpdateRequestedAt()).isNotNull();
        assertThat(dto.updateRequested()).isTrue();
        assertThat(dto.scriptUpToDate()).isFalse();
        verify(runnerMapper).updateById(runner);
    }

    @Test
    void heartbeatStoresOnlyTheBoundedRunnerLogTail() {
        AiRunnerNodeEntity runner = runner("rn1", "娃哥电脑", "codex");
        runner.setTokenHash("hashed-token");
        when(runnerMapper.selectOne(any())).thenReturn(runner);
        when(runnerMapper.selectList(any())).thenReturn(List.of(runner));
        when(runMapper.selectList(any())).thenReturn(List.of());
        String oversized = "prefix-" + "x".repeat(30_000) + "-latest";

        service.heartbeat("local-token", null, null, null, null, null, oversized, null, null);

        assertThat(runner.getLogTail()).hasSize(30_000).endsWith("-latest");
        assertThat(runner.getLogUpdatedAt()).isNotNull();
        verify(runnerMapper).update(eq(runner), any());
        verify(runnerMapper, never()).updateById(runner);
    }

    @Test
    void heartbeatPassesTheLocalCodexQuotaToTheCredentialFreeSnapshotService() {
        AiRunnerNodeEntity runner = runner("rn1", "娃哥电脑", "codex");
        runner.setTokenHash("hashed-token");
        when(runnerMapper.selectOne(any())).thenReturn(runner);
        when(runnerMapper.selectList(any())).thenReturn(List.of(runner));
        when(runMapper.selectList(any())).thenReturn(List.of());
        RunnerCodexQuotaReport report = mock(RunnerCodexQuotaReport.class);

        service.heartbeat("local-token", null, null, null, null, null, null, report, null);

        verify(codexQuotas).report(eq("rn1"), eq(report), isNull(), any(LocalDateTime.class));
    }

    @Test
    void runnerLogReturnsTheLastSnapshotWithoutExposingItInRunnerList() {
        AiRunnerNodeEntity runner = runner("rn1", "娃哥电脑", "codex");
        runner.setLogTail("2026-07-31 Runner 已启动");
        runner.setLogUpdatedAt(LocalDateTime.of(2026, 7, 31, 13, 30));
        when(runnerMapper.selectById("rn1")).thenReturn(runner);

        var result = service.runnerLog("rn1");

        assertThat(result.runnerName()).isEqualTo("娃哥电脑");
        assertThat(result.content()).contains("Runner 已启动");
        assertThat(result.updatedAt()).isEqualTo("2026-07-31T13:30");
    }

    @Test
    void requestScriptUpdateFlagsOnlyOutdatedRunners() {
        AiRunnerNodeEntity stale = runner("rn1", "娃哥电脑", "codex");
        stale.setScriptHash("old-hash");
        AiRunnerNodeEntity fresh = runner("rn2", "Linux 主节点", "claude");
        fresh.setScriptHash("latest-hash");
        when(runnerMapper.selectList(any())).thenReturn(List.of(stale, fresh));
        when(runMapper.selectList(any())).thenReturn(List.of());
        when(scripts.available()).thenReturn(true);
        when(scripts.hash()).thenReturn("latest-hash");

        service.requestScriptUpdate();

        assertThat(stale.getUpdateRequestedAt()).isNotNull();
        assertThat(fresh.getUpdateRequestedAt()).isNull();
        verify(runnerMapper, times(1)).updateById(stale);
        verify(runnerMapper, never()).updateById(fresh);
    }

    @Test
    void requestScriptUpdateFailsWhenScriptIsNotBundled() {
        when(scripts.available()).thenReturn(false);

        assertThatThrownBy(service::requestScriptUpdate)
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("没有打包 Runner 脚本");
    }

    @Test
    void claimUsesAdminMaxConcurrencyAsSlotLimit() {
        AiRunnerNodeEntity runner = runner("rn1", "娃哥电脑", "codex");
        runner.setMaxConcurrency(2);
        runner.setAdminMaxConcurrency(1);
        runner.setLastSeenAt(LocalDateTime.now());
        AiTaskRunEntity existing = new AiTaskRunEntity();
        existing.setId("ar0");
        existing.setStatus("running");
        existing.setRunnerId("rn1");
        when(runnerMapper.selectOne(any())).thenReturn(runner);
        when(runMapper.selectList(any())).thenReturn(List.of(existing), List.of());
        when(runMapper.selectCount(any())).thenReturn(1L);

        assertThat(service.claim("local-token")).isNull();
    }

    @Test
    void updateRunnerMaxConcurrencyStoresAdminValue() {
        AiRunnerNodeEntity runner = runner("rn1", "娃哥电脑", "codex");
        runner.setMaxConcurrency(2);
        when(runnerMapper.selectById("rn1")).thenReturn(runner);
        when(runnerMapper.selectList(any())).thenReturn(List.of(runner));
        when(runMapper.selectList(any())).thenReturn(List.of());

        var result = service.updateRunnerMaxConcurrency("rn1", 6);

        assertThat(runner.getAdminMaxConcurrency()).isEqualTo(6);
        assertThat(result.maxConcurrency()).isEqualTo(6);
    }

    @Test
    void runnerProgressIsArchivedAndUpdatesLatestSummary() {
        AiRunnerNodeEntity runner = new AiRunnerNodeEntity();
        runner.setId("rn1");
        AiTaskRunEntity run = new AiTaskRunEntity();
        run.setId("ar1");
        run.setRunnerId("rn1");
        run.setStatus("running");
        when(runnerMapper.selectOne(any())).thenReturn(runner);
        when(runMapper.selectById("ar1")).thenReturn(run);

        service.reportProgress("local-token", "ar1",
                new ReportAiRunProgressRequest("message", "已定位失败原因，正在修复"));

        ArgumentCaptor<AiRunProgressEntity> progress = ArgumentCaptor.forClass(AiRunProgressEntity.class);
        verify(progressMapper).insert(progress.capture());
        assertThat(progress.getValue().getContent()).isEqualTo("已定位失败原因，正在修复");
        assertThat(run.getLatestProgress()).isEqualTo("已定位失败原因，正在修复");
        assertThat(run.getProgressUpdatedAt()).isNotNull();
    }

    // ---------------- 基于报错重跑 ----------------

    @Test
    void rerunQueuesANewRunPointingBackAtTheFailedOne() {
        AiTaskRunEntity failed = new AiTaskRunEntity();
        failed.setId("ar1");
        failed.setTaskId("t1");
        failed.setProjectId("p1");
        failed.setEngine("codex");
        failed.setStatus("failed");
        failed.setPromptUrl("https://wao.example/api/share/tasks/t1.md");
        failed.setResultSummary("部署失败：容器健康检查未通过");
        when(runMapper.selectById("ar1")).thenReturn(failed);
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(configMapper.selectById("p1")).thenReturn(config("p1"));
        when(runnerMapper.selectList(null)).thenReturn(List.of(runner("rn1", "娃哥电脑", "codex")));
        when(taskMapper.selectById("t1")).thenReturn(task("t1", "p1", "完成自动化"));
        when(projectMapper.selectById("p1")).thenReturn(project("p1", "WAO"));
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task("t1", "p1", "完成自动化")));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));

        var dto = service.rerun("ar1", "wg", "https://wao.example");

        ArgumentCaptor<AiTaskRunEntity> created = ArgumentCaptor.forClass(AiTaskRunEntity.class);
        verify(runMapper).insert(created.capture());
        assertThat(created.getValue().getRetryOfRunId()).isEqualTo("ar1");
        assertThat(created.getValue().getTaskId()).isEqualTo("t1");
        assertThat(created.getValue().getStatus()).isEqualTo("queued");
        assertThat(created.getValue().getBranchName()).isNotEqualTo("ai/wao-ar1");
        assertThat(dto.retryOfRunId()).isEqualTo("ar1");
    }

    @Test
    void rerunRefusesWhileTheRunIsStillGoing() {
        AiTaskRunEntity running = new AiTaskRunEntity();
        running.setId("ar1");
        running.setTaskId("t1");
        running.setProjectId("p1");
        running.setStatus("running");
        when(runMapper.selectById("ar1")).thenReturn(running);

        assertThatThrownBy(() -> service.rerun("ar1", "wg", "https://wao.example"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("还没结束");
        verify(runMapper, never()).insert(any(AiTaskRunEntity.class));
    }

    @Test
    void continueRunQueuesOriginalSessionOnItsOriginalRunner() {
        AiTaskRunEntity failed = new AiTaskRunEntity();
        failed.setId("ar1");
        failed.setTaskId("t1");
        failed.setProjectId("p1");
        failed.setStatus("failed");
        failed.setRunnerId("rn1");
        failed.setSessionId("thread-1");
        when(runMapper.selectById("ar1")).thenReturn(failed);
        when(runnerMapper.selectById("rn1")).thenReturn(runner("rn1", "娃哥电脑", "codex"));
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task("t1", "p1", "修复自动化")));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));

        var dto = service.continueRun("ar1", "请按测试报错继续修复", "wg", "https://wao.example");

        assertThat(dto.status()).isEqualTo("queued");
        assertThat(failed.getRequestedRunnerId()).isEqualTo("rn1");
        assertThat(failed.getPendingInput()).isEqualTo("请按测试报错继续修复");
        verify(runMapper, times(2)).updateById(failed);
    }

    @Test
    void continueRunWhileActiveWaitsForTheOwningRunnerAndCanBeConsumedExactlyOnce() {
        AiTaskRunEntity running = runningRun();

        var dto = service.continueRun("ar1", "先保证手机端不跳动，再处理桌面样式", "wg",
                "https://wao.example");

        assertThat(dto.status()).isEqualTo("running");
        assertThat(dto.continuationPending()).isTrue();
        assertThat(service.control("local-token", "ar1").continuationInput())
                .isEqualTo("先保证手机端不跳动，再处理桌面样式");

        service.consumeContinuation("local-token", "ar1", "先保证手机端不跳动，再处理桌面样式");

        assertThat(running.getPendingInput()).isNull();
        assertThat(service.control("local-token", "ar1").continuationInput()).isNull();
        assertThat(running.getLatestProgress()).contains("已接收新输入");
    }

    @Test
    void activeRunRefusesToOverwriteAnInputThatTheRunnerHasNotReceived() {
        AiTaskRunEntity running = runningRun();
        running.setPendingInput("第一条补充");

        assertThatThrownBy(() -> service.continueRun("ar1", "第二条补充", "wg", "https://wao.example"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("上一条补充");

        assertThat(running.getPendingInput()).isEqualTo("第一条补充");
    }

    @Test
    void completionRacesWithNewInputByRequeueingTheSameSessionInsteadOfDroppingIt() {
        AiTaskRunEntity running = runningRun();
        service.continueRun("ar1", "验收时请重点检查窄屏", "wg", "https://wao.example");

        var result = service.complete("local-token", "ar1",
                new CompleteAiRunRequest(0, "WAO_RUN_STATUS: SUCCESS", "sess-live", null, null,
                        null, null, null, null, null));

        assertThat(result.status()).isEqualTo("queued");
        assertThat(result.continuationPending()).isTrue();
        assertThat(running.getSessionId()).isEqualTo("sess-live");
        assertThat(running.getPendingInput()).isEqualTo("验收时请重点检查窄屏");
        assertThat(running.getRequestedRunnerId()).isEqualTo("rn1");
    }

    @Test
    void claimHandsThePreviousFailureToTheRunner() {
        AiRunnerNodeEntity runner = runner("rn1", "娃哥电脑", "codex");
        runner.setMaxConcurrency(2);
        runner.setLastSeenAt(LocalDateTime.now());
        AiTaskRunEntity retry = new AiTaskRunEntity();
        retry.setId("ar2");
        retry.setTaskId("t1");
        retry.setProjectId("p1");
        retry.setStatus("queued");
        retry.setEngine("codex");
        retry.setPromptUrl("https://wao.example/api/share/tasks/t1.md");
        retry.setBranchName("ai/wao-ar2");
        retry.setRetryOfRunId("ar1");
        AiTaskRunEntity failed = new AiTaskRunEntity();
        failed.setId("ar1");
        failed.setResultSummary("部署失败：容器健康检查未通过");
        when(runnerMapper.selectOne(any())).thenReturn(runner);
        when(runMapper.selectList(any())).thenReturn(List.of(), List.of(retry));
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(runMapper.selectById("ar1")).thenReturn(failed);
        when(configMapper.selectById("p1")).thenReturn(config("p1"));
        when(taskMapper.selectById("t1")).thenReturn(task("t1", "p1", "完成自动化"));
        when(projectMapper.selectById("p1")).thenReturn(project("p1", "WAO"));

        AiRunnerClaimDto claim = service.claim("local-token");

        assertThat(claim.previousFailure()).isEqualTo("部署失败：容器健康检查未通过");
    }

    // ---------------- 解除电脑接入 ----------------

    @Test
    void removingARunnerDeletesItAndReleasesEverythingPinnedToIt() {
        AiRunnerNodeEntity runner = runner("rn1", "娃哥Windows电脑", "codex", "claude");
        when(runnerMapper.selectById("rn1")).thenReturn(runner);
        when(runnerMapper.selectList(any())).thenReturn(List.of(runner));
        when(runMapper.selectList(any())).thenReturn(List.of());
        when(runMapper.selectCount(any())).thenReturn(0L);

        AiRunnerDto removed = service.removeRunner("rn1");

        assertThat(removed.name()).isEqualTo("娃哥Windows电脑");
        assertThat(removed.engines()).containsExactly("codex", "claude");
        verify(runnerMapper).deleteById("rn1");
        // 排队中点名这台电脑的运行、以及把它设成首选电脑的项目配置都要放开，否则永远没人领
        verify(runMapper).update(isNull(), any());
        verify(configMapper).update(isNull(), any());
    }

    @Test
    void removingARunnerIsRefusedWhileItStillRunsTasks() {
        when(runnerMapper.selectById("rn1")).thenReturn(runner("rn1", "娃哥Windows电脑", "codex"));
        when(runnerMapper.selectList(any())).thenReturn(List.of(runner("rn1", "娃哥Windows电脑", "codex")));
        when(runMapper.selectList(any())).thenReturn(List.of());
        when(runMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.removeRunner("rn1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("还有 2 条 AI 任务在执行");
        verify(runnerMapper, never()).deleteById("rn1");
    }

    @Test
    void continuingASessionOnARemovedComputerIsRefusedInsteadOfQueuedForever() {
        AiTaskRunEntity succeeded = new AiTaskRunEntity();
        succeeded.setId("ar1");
        succeeded.setTaskId("t1");
        succeeded.setProjectId("p1");
        succeeded.setStatus("succeeded");
        succeeded.setRunnerId("rn1");
        succeeded.setSessionId("thread-1");
        when(runMapper.selectById("ar1")).thenReturn(succeeded);
        when(runnerMapper.selectById("rn1")).thenReturn(null);

        assertThatThrownBy(() -> service.continueRun("ar1", "再优化一版", "wg", "https://wao.example"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("已解除接入");
        assertThat(succeeded.getStatus()).isEqualTo("succeeded");
    }

    @Test
    void historyKeepsSayingWhichComputerRanItEvenAfterItWasRemoved() {
        AiTaskRunEntity run = new AiTaskRunEntity();
        run.setId("ar1");
        run.setTaskId("t1");
        run.setProjectId("p1");
        run.setStatus("succeeded");
        run.setRunnerId("rn-gone");
        // 第一次 selectList 是自动回收扫描，第二次才是列表查询
        when(runMapper.selectList(any())).thenReturn(List.of(), List.of(run));
        when(runnerMapper.selectBatchIds(any())).thenReturn(List.of());
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task("t1", "p1", "记录用量")));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));

        var runs = service.listRuns(false);

        assertThat(runs.get(0).runnerName()).isEqualTo("已解除接入的电脑");
    }

    private static AiRunnerNodeEntity runner(String id, String name, String... engines) {
        AiRunnerNodeEntity runner = new AiRunnerNodeEntity();
        runner.setId(id);
        runner.setName(name);
        runner.setPlatform(name.contains("Linux") ? "Linux" : "Windows");
        runner.setEngines(String.join(",", engines));
        runner.setMaxConcurrency(1);
        return runner;
    }

    private static AiTaskRunTaskEntity runTask(String runId, String taskId, int ord) {
        AiTaskRunTaskEntity link = new AiTaskRunTaskEntity();
        link.setRunId(runId);
        link.setTaskId(taskId);
        link.setOrd(ord);
        return link;
    }

    @Test
    void completeRecordsModelTokensAndCost() {
        AiTaskRunEntity run = runningRun();

        AiRunDto result = service.complete("local-token", "ar1", new CompleteAiRunRequest(
                0, "WAO_RUN_STATUS: SUCCESS", "sess-1", "claude-opus-5", "claude-opus-5",
                2L, 4L, 19_189L, 19_195L, new BigDecimal("0.087053")));

        assertThat(run.getModel()).isEqualTo("claude-opus-5");
        assertThat(run.getTotalTokens()).isEqualTo(19_195L);
        assertThat(run.getCostUsd()).isEqualByComparingTo("0.087053");
        assertThat(result.usage().recorded()).isTrue();
        assertThat(result.usage().model()).isEqualTo("claude-opus-5");
    }

    @Test
    void continuationAccumulatesOntoTheSameRunBill() {
        AiTaskRunEntity run = runningRun();
        run.setInputTokens(10L);
        run.setOutputTokens(20L);
        run.setCachedInputTokens(30L);
        run.setTotalTokens(60L);
        run.setCostUsd(new BigDecimal("0.10"));
        run.setModel("claude-haiku-4-5-20251001");
        run.setModels("claude-haiku-4-5-20251001");

        service.complete("local-token", "ar1", new CompleteAiRunRequest(
                0, "done", "sess-1", "claude-opus-5", "claude-opus-5",
                1L, 2L, 3L, 6L, new BigDecimal("0.05")));

        assertThat(run.getTotalTokens()).isEqualTo(66L);
        assertThat(run.getCostUsd()).isEqualByComparingTo("0.15");
        assertThat(run.getModels()).isEqualTo("claude-haiku-4-5-20251001,claude-opus-5");
    }

    @Test
    void emptyUsageDoesNotWipeAnAlreadyRecordedBill() {
        AiTaskRunEntity run = runningRun();
        run.setTotalTokens(19_195L);
        run.setCostUsd(new BigDecimal("0.087053"));
        run.setModel("claude-opus-5");

        // Runner 解析不出用量时回传全 0，这次不能把先前记好的账抹掉。
        service.complete("local-token", "ar1",
                new CompleteAiRunRequest(0, "done", "sess-1", null, null, 0L, 0L, 0L, 0L, null));

        assertThat(run.getTotalTokens()).isEqualTo(19_195L);
        assertThat(run.getCostUsd()).isEqualByComparingTo("0.087053");
        assertThat(run.getModel()).isEqualTo("claude-opus-5");
    }

    @Test
    void runWithoutUsageIsMarkedAsNotRecordedRatherThanZero() {
        runningRun();

        AiRunDto result = service.complete("local-token", "ar1",
                new CompleteAiRunRequest(0, "done", "sess-1", null, null, null, null, null, null, null));

        assertThat(result.usage().recorded()).isFalse();
        assertThat(result.usage().totalTokens()).isZero();
    }

    // ---------------- 子任务级派工 ----------------

    @Test
    void enqueueCanTargetASingleSubtask() {
        TaskEntity task = task("t1", "p1", "AI run 任务优化");
        when(taskMapper.selectById("t1")).thenReturn(task);
        when(configMapper.selectById("p1")).thenReturn(config("p1"));
        when(runnerMapper.selectList(any())).thenReturn(List.of(runner("rn1", "娃哥电脑", "codex")));
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));
        when(tasks.get("t1")).thenReturn(taskDto("s1", "子任务也能单独跑"));

        var result = service.enqueue(List.of("t1"), "s1", null, null, null, "wg", "https://wao.example");

        assertThat(result).hasSize(1);
        ArgumentCaptor<AiTaskRunEntity> run = ArgumentCaptor.forClass(AiTaskRunEntity.class);
        verify(runMapper).insert(run.capture());
        assertThat(run.getValue().getSubId()).isEqualTo("s1");
        assertThat(run.getValue().getPromptUrl())
                .isEqualTo("https://wao.example/api/share/tasks/t1/subs/s1.md");
    }

    @Test
    void enqueueRejectsAnUnknownSubtask() {
        when(taskMapper.selectById("t1")).thenReturn(task("t1", "p1", "AI run 任务优化"));
        when(configMapper.selectById("p1")).thenReturn(config("p1"));
        when(runnerMapper.selectList(any())).thenReturn(List.of(runner("rn1", "娃哥电脑", "codex")));
        when(tasks.get("t1")).thenReturn(taskDto("s1", "子任务也能单独跑"));

        assertThatThrownBy(() -> service.enqueue(List.of("t1"), "s9", null, null, null, "wg", "https://wao.example"))
                .isInstanceOf(cn.wayuwa.wao.common.NotFoundException.class);
        verify(runMapper, never()).insert(any(AiTaskRunEntity.class));
    }

    @Test
    void dispatchedSubIdsListsSubtasksThatAlreadyHaveARun() {
        AiTaskRunEntity subRun = new AiTaskRunEntity();
        subRun.setId("ar1");
        subRun.setTaskId("t1");
        subRun.setSubId("s1");
        when(runMapper.selectList(any())).thenReturn(List.of(subRun));

        assertThat(service.dispatchedSubIds("t1")).containsExactly("s1");
    }

    // ---------------- 跑完之后继续优化 ----------------

    @Test
    void continueRunAlsoWorksOnASucceededRunSoItCanBeIterated() {
        AiTaskRunEntity succeeded = new AiTaskRunEntity();
        succeeded.setId("ar1");
        succeeded.setTaskId("t1");
        succeeded.setProjectId("p1");
        succeeded.setStatus("succeeded");
        succeeded.setRunnerId("rn1");
        succeeded.setSessionId("thread-1");
        when(runMapper.selectById("ar1")).thenReturn(succeeded);
        when(runnerMapper.selectById("rn1")).thenReturn(runner("rn1", "娃哥电脑", "codex"));
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task("t1", "p1", "移动端体验")));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));

        var dto = service.continueRun("ar1", "移动端弹窗还是会跳动，继续优化", "wg", "https://wao.example");

        assertThat(dto.status()).isEqualTo("queued");
        assertThat(succeeded.getPendingInput()).isEqualTo("移动端弹窗还是会跳动，继续优化");
        assertThat(succeeded.getRequestedRunnerId()).isEqualTo("rn1");
    }

    @Test
    void continueRunAbsolutizesPastedInSiteImagesSoTheAiCanFetchThem() {
        AiTaskRunEntity succeeded = new AiTaskRunEntity();
        succeeded.setId("ar1");
        succeeded.setTaskId("t1");
        succeeded.setProjectId("p1");
        succeeded.setStatus("succeeded");
        succeeded.setRunnerId("rn1");
        succeeded.setSessionId("thread-1");
        when(runMapper.selectById("ar1")).thenReturn(succeeded);
        when(runnerMapper.selectById("rn1")).thenReturn(runner("rn1", "娃哥电脑", "codex"));
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task("t1", "p1", "移动端体验")));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));

        service.continueRun("ar1", "按截图改：![问题截图](/api/images/img1/raw)", "wg", "https://wao.example");

        assertThat(succeeded.getPendingInput())
                .isEqualTo("按截图改：![问题截图](https://wao.example/api/images/img1/raw)");
    }

    @Test
    void completedRunAsksTheGroupToVerifyIt() {
        runningRun();

        service.complete("local-token", "ar1",
                new CompleteAiRunRequest(0, "WAO_RUN_STATUS: SUCCESS", "sess-1", null, null,
                        null, null, null, null, null));

        verify(notifier).notifyRunFinished(eq("t1"), eq("p1"), eq("记录用量"), any(),
                eq("Claude Code"), eq("整个任务"), eq(true), eq(false), any());
    }

    @Test
    void mergedCompletionMovesEveryRelatedTaskToVerification() {
        AiTaskRunEntity run = runningRun();
        TaskEntity first = taskMapper.selectById("t1");
        TaskEntity second = task("t2", "p1", "补齐移动端验收");
        when(runMapper.selectTaskLinks(any())).thenReturn(List.of(
                runTask(run.getId(), "t1", 0), runTask(run.getId(), "t2", 1)));
        when(taskMapper.selectById("t2")).thenReturn(second);
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(first, second));

        service.complete("local-token", "ar1",
                new CompleteAiRunRequest(0, "WAO_RUN_STATUS: SUCCESS", "sess-1", null, null,
                        null, null, null, null, null));

        assertThat(first.getStatus()).isEqualTo("待验证");
        assertThat(second.getStatus()).isEqualTo("待验证");
        verify(notifier).notifyRunFinished(eq("t1"), eq("p1"), eq("记录用量"), any(),
                eq("Claude Code"), eq("合并 2 个任务"), eq(true), eq(false), any());
    }

    @Test
    void successfulRunPersistsStructuredMemoryCandidates() {
        AiTaskRunEntity run = runningRun();
        var candidates = List.of(new ProjectMemoryCandidate("verification", "后端验收命令",
                "执行 mvn test 后全部通过。", List.of("https://github.com/acme/wao/pull/1")));

        service.complete("local-token", "ar1",
                new CompleteAiRunRequest(0, "WAO_RUN_STATUS: SUCCESS", "sess-1", null, null,
                        null, null, null, null, null, candidates));

        verify(projectMemories).persistCandidates(run, candidates);
    }

    @Test
    void failedRunNeverPersistsMemoryCandidates() {
        runningRun();
        var candidates = List.of(new ProjectMemoryCandidate("context", "未经验证结论",
                "这条内容来自失败运行。", List.of()));

        service.complete("local-token", "ar1",
                new CompleteAiRunRequest(1, "WAO_RUN_STATUS: BLOCKED", "sess-1", null, null,
                        null, null, null, null, null, candidates));

        verify(projectMemories, never()).persistCandidates(any(), any());
    }

    @Test
    void zeroExitWithoutVerifiedStatusNeverPersistsMemoryCandidates() {
        runningRun();
        var candidates = List.of(new ProjectMemoryCandidate("context", "未确认结论",
                "CLI 退出了，但没有证明 PR、部署和线上验收完成。", List.of("https://example.com/run")));

        service.complete("local-token", "ar1",
                new CompleteAiRunRequest(0, "会话正常退出", "sess-1", null, null,
                        null, null, null, null, null, candidates));

        verify(projectMemories, never()).persistCandidates(any(), any());
    }

    @Test
    void unreportedStatusIsAnnouncedAsUnconfirmedInsteadOfFailure() {
        AiTaskRunEntity run = runningRun();

        // 退出码 3 是 Runner 的约定：AI 结束了会话但没报告任务状态，提示不能笼统说成失败。
        service.complete("local-token", "ar1",
                new CompleteAiRunRequest(3, "Qoder 已结束会话，但最终答复没有按约定报告任务状态", "sess-1",
                        null, null, null, null, null, null, null));

        assertThat(run.getStatus()).isEqualTo("failed");
        assertThat(run.getLatestProgress()).contains("未报告任务状态").doesNotContain("失败原因");
        verify(notifier).notifyRunFinished(eq("t1"), eq("p1"), eq("记录用量"), any(),
                eq("Claude Code"), eq("整个任务"), eq(false), eq(true), any());
    }

    /** 只带一条子任务的任务视图，供子任务派工用。 */
    private static cn.wayuwa.wao.dto.TaskDto taskDto(String subId, String subTitle) {
        return new cn.wayuwa.wao.dto.TaskDto("t1", "AI run 任务优化", "p1", "进行中", "P2", "wg",
                List.of(), null, null, null, "", List.of(),
                List.of(new cn.wayuwa.wao.dto.Step(subTitle, false, subId, "", List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** 一条已被 rn1 领取、正在运行的记录，complete 的前置条件。 */
    private AiTaskRunEntity runningRun() {
        AiRunnerNodeEntity runner = runner("rn1", "Linux主节点", "claude");
        runner.setLastSeenAt(LocalDateTime.now());
        AiTaskRunEntity run = new AiTaskRunEntity();
        run.setId("ar1");
        run.setTaskId("t1");
        run.setProjectId("p1");
        run.setEngine("claude");
        run.setStatus("running");
        run.setRunnerId("rn1");
        when(runnerMapper.selectOne(any())).thenReturn(runner);
        when(runMapper.selectById("ar1")).thenReturn(run);
        when(runMapper.selectList(any())).thenReturn(List.of());
        when(taskMapper.selectById("t1")).thenReturn(task("t1", "p1", "记录用量"));
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task("t1", "p1", "记录用量")));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project("p1", "WAO")));
        when(runnerMapper.selectBatchIds(any())).thenReturn(List.of(runner));
        return run;
    }

    private static ProjectEntity project(String id, String name) {
        ProjectEntity project = new ProjectEntity();
        project.setId(id);
        project.setName(name);
        return project;
    }

    private static TaskEntity task(String id, String projectId, String title) {
        TaskEntity task = new TaskEntity();
        task.setId(id);
        task.setProjectId(projectId);
        task.setTitle(title);
        task.setStatus("进行中");
        return task;
    }

    private static AiProjectConfigEntity config(String projectId) {
        AiProjectConfigEntity config = new AiProjectConfigEntity();
        config.setProjectId(projectId);
        config.setEnabled(true);
        config.setRepoUrl("https://github.com/acme/wao.git");
        config.setDefaultBranch("main");
        config.setWorkspaceDir("wao");
        config.setDefaultEngine("codex");
        config.setMaxParallelRuns(2);
        return config;
    }
}
