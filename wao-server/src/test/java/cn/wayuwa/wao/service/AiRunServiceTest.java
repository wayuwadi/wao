package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.AiProjectConfigEntity;
import cn.wayuwa.wao.domain.AiRunProgressEntity;
import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import cn.wayuwa.wao.domain.AiRunnerPairCodeEntity;
import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.AiRunnerClaimDto;
import cn.wayuwa.wao.dto.PairedAiRunnerDto;
import cn.wayuwa.wao.dto.PairAiRunnerRequest;
import cn.wayuwa.wao.dto.SaveAiProjectConfigRequest;
import cn.wayuwa.wao.dto.ReportAiRunProgressRequest;
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

class AiRunServiceTest {

    private final AiProjectConfigMapper configMapper = mock(AiProjectConfigMapper.class);
    private final AiRunnerNodeMapper runnerMapper = mock(AiRunnerNodeMapper.class);
    private final AiRunnerPairCodeMapper pairMapper = mock(AiRunnerPairCodeMapper.class);
    private final AiTaskRunMapper runMapper = mock(AiTaskRunMapper.class);
    private final AiRunProgressMapper progressMapper = mock(AiRunProgressMapper.class);
    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final AiRunService service = new AiRunService(
            configMapper, runnerMapper, pairMapper, runMapper, progressMapper, taskMapper, projectMapper);

    @BeforeEach
    void emptyProgressByDefault() {
        when(progressMapper.selectList(any())).thenReturn(List.of());
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

        var result = service.enqueue(List.of("t1"), null, null, "wg", "https://wao.example");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).taskTitle()).isEqualTo("修复登录");
        assertThat(result.get(0).engine()).isEqualTo("codex");
        assertThat(task.getStatus()).isEqualTo("AI 进行中");
        ArgumentCaptor<AiTaskRunEntity> run = ArgumentCaptor.forClass(AiTaskRunEntity.class);
        verify(runMapper).insert(run.capture());
        assertThat(run.getValue().getPromptUrl()).isEqualTo("https://wao.example/api/share/tasks/t1.md");
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

        var result = service.enqueue(List.of("t1"), "claude", "rn2", "wg", "https://wao.example");

        assertThat(result.get(0).engine()).isEqualTo("claude");
        assertThat(result.get(0).requestedRunnerId()).isEqualTo("rn2");
        ArgumentCaptor<AiTaskRunEntity> run = ArgumentCaptor.forClass(AiTaskRunEntity.class);
        verify(runMapper).insert(run.capture());
        assertThat(run.getValue().getEngine()).isEqualTo("claude");
        assertThat(run.getValue().getRequestedRunnerId()).isEqualTo("rn2");
    }

    @Test
    void enqueueRejectsComputerThatDoesNotHaveTheRequestedAiTool() {
        when(taskMapper.selectById("t1")).thenReturn(task("t1", "p1", "接入 Claude"));
        when(configMapper.selectById("p1")).thenReturn(config("p1"));
        when(runnerMapper.selectById("rn1")).thenReturn(runner("rn1", "娃哥电脑", "codex"));

        assertThatThrownBy(() -> service.enqueue(List.of("t1"), "claude", "rn1", "wg", "https://wao.example"))
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
        assertThat(queued.getStatus()).isEqualTo("running");
        assertThat(queued.getRunnerId()).isEqualTo("rn1");
        verify(runMapper).updateById(eq(queued));
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

    private static AiRunnerNodeEntity runner(String id, String name, String... engines) {
        AiRunnerNodeEntity runner = new AiRunnerNodeEntity();
        runner.setId(id);
        runner.setName(name);
        runner.setEngines(String.join(",", engines));
        runner.setMaxConcurrency(1);
        return runner;
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
