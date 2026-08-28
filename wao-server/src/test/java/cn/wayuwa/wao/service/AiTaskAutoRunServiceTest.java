package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import cn.wayuwa.wao.domain.AiTaskAutoRunStateEntity;
import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.AiProjectConfigDto;
import cn.wayuwa.wao.dto.AiRunDto;
import cn.wayuwa.wao.dto.AiTaskAutoRunPolicyDto;
import cn.wayuwa.wao.mapper.AiTaskAutoRunStateMapper;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

class AiTaskAutoRunServiceTest {

    @BeforeAll
    static void initializeLambdaMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AiTaskAutoRunStateEntity.class);
        TableInfoHelper.initTableInfo(assistant, AiTaskRunEntity.class);
    }

    private final AiTaskAutoRunStateMapper stateMapper = mock(AiTaskAutoRunStateMapper.class);
    private final AiTaskRunMapper runMapper = mock(AiTaskRunMapper.class);
    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final AiRunService runs = mock(AiRunService.class);
    private final FeishuTaskNotifier notifier = mock(FeishuTaskNotifier.class);
    private final FeishuWebhookConfigService webhooks = mock(FeishuWebhookConfigService.class);
    private final NetworkOutageWindowService networkOutageWindow = mock(NetworkOutageWindowService.class);
    private final AiTaskAutoRunService service = new AiTaskAutoRunService(
            stateMapper, runMapper, taskMapper, projectMapper, runs, notifier, webhooks,
            networkOutageWindow, new ObjectMapper(), "https://wao.example/");
    private AiTaskAutoRunStateEntity state;
    private ProjectEntity project;

    @BeforeEach
    void setUp() {
        state = new AiTaskAutoRunStateEntity();
        state.setId("oldest-task");
        state.setEnabled(true);
        state.setIdleHours(2);
        state.setBatchSize(2);
        state.setProjectId("p-wao");
        state.setTaskStatusesJson("[\"待办\"]");
        state.setNotificationWebhookId("fw-auto");
        project = new ProjectEntity();
        project.setId("p-wao");
        project.setName("Wa All in One");
        when(stateMapper.selectById("oldest-task")).thenReturn(state);
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectOldestAutoRunCandidates(any(), anyList(), anyInt())).thenReturn(List.of());
        when(projectMapper.selectById("p-wao")).thenReturn(project);
        when(runs.getConfig("p-wao")).thenReturn(new AiProjectConfigDto(
                "p-wao", true, "https://github.com/example/wao.git", "main", "wao", "codex", null, 2, 2));
    }

    @Test
    void overviewExplainsWhenNoRunnableTaskExists() {
        AiTaskAutoRunPolicyDto overview = service.overview();

        assertThat(overview.enabled()).isTrue();
        assertThat(overview.idleHours()).isEqualTo(2);
        assertThat(overview.batchSize()).isEqualTo(2);
        assertThat(overview.projectId()).isEqualTo("p-wao");
        assertThat(overview.taskStatuses()).containsExactly("待办");
        assertThat(overview.notificationWebhookId()).isEqualTo("fw-auto");
        assertThat(overview.status()).isEqualTo("no_task");
        assertThat(overview.message()).contains("Wa All in One", "待办");
    }

    @Test
    void dueCheckLaunchesOldestCandidatesAsIndependentPlanRuns() {
        TaskEntity oldest = task("t-oldest", "最早录入的待办");
        TaskEntity second = task("t-second", "第二条待办");
        AiRunDto oldestRun = run("ar-oldest", oldest);
        AiRunDto secondRun = run("ar-second", second);
        when(stateMapper.claimDueCheck()).thenReturn(1);
        when(taskMapper.selectOldestAutoRunCandidates("p-wao", List.of("待办"), 2))
                .thenReturn(List.of(oldest, second));
        when(runs.enqueue(eq(List.of("t-oldest", "t-second")), eq(null), eq(null), eq(null), eq(null),
                eq(null), eq(null), eq(true), eq(AiRunService.IDLE_AUTO_RUN_REQUESTED_BY),
                eq("https://wao.example")))
                .thenReturn(List.of(oldestRun, secondRun));
        when(notifier.notifyIdleAutoRun("t-oldest", "p-wao", "最早录入的待办",
                "Wa All in One", 2, "fw-auto")).thenReturn(true);
        when(notifier.notifyIdleAutoRun("t-second", "p-wao", "第二条待办",
                "Wa All in One", 2, "fw-auto")).thenReturn(true);

        service.checkAndLaunch();

        verify(runs).enqueue(eq(List.of("t-oldest", "t-second")), eq(null), eq(null), eq(null), eq(null),
                eq(null), eq(null), eq(true), eq(AiRunService.IDLE_AUTO_RUN_REQUESTED_BY),
                eq("https://wao.example"));
        verify(notifier).notifyIdleAutoRun("t-oldest", "p-wao", "最早录入的待办",
                "Wa All in One", 2, "fw-auto");
        verify(notifier).notifyIdleAutoRun("t-second", "p-wao", "第二条待办",
                "Wa All in One", 2, "fw-auto");
        verify(stateMapper).update(eq(null), any());
    }

    @Test
    void reducedProjectConcurrencyCapsAnOlderLargerBatchSetting() {
        state.setBatchSize(5);
        when(taskMapper.selectOldestAutoRunCandidates("p-wao", List.of("待办"), 1))
                .thenReturn(List.of(task("t-oldest", "最早录入的待办")));

        AiTaskAutoRunPolicyDto overview = service.overview();

        assertThat(overview.batchSize()).isEqualTo(2);
        assertThat(overview.message()).contains("最早的 2 条");
    }

    @Test
    void aNodeThatLosesTheDatabaseClaimDoesNothing() {
        when(stateMapper.claimDueCheck()).thenReturn(0);

        service.checkAndLaunch();

        verify(taskMapper, never()).selectOldestAutoRunCandidates(any(), anyList(), anyInt());
        verify(runs, never()).enqueue(anyList(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void outageWindowDoesNotConsumeTheDatabaseCheckSlot() {
        when(networkOutageWindow.isUnavailable()).thenReturn(true);

        service.checkAndLaunch();

        verify(stateMapper, never()).claimDueCheck();
        verify(runs, never()).enqueue(anyList(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void saveRejectsStatusesThatWouldRerunCompletedTasks() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.save(true, 2, 2, "p-wao", List.of("已完成"), "fw-auto"))
                .isInstanceOf(cn.wayuwa.wao.common.BadRequestException.class)
                .hasMessageContaining("只支持待办、收集中或进行中");
    }

    @Test
    void saveRejectsBatchAboveSelectedProjectConcurrency() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.save(true, 2, 3, "p-wao", List.of("待办"), "fw-auto"))
                .isInstanceOf(cn.wayuwa.wao.common.BadRequestException.class)
                .hasMessageContaining("不能超过所选项目的并发上限 2");
    }

    @Test
    void legacySaveWithoutBatchUsesTheSelectedProjectConcurrency() {
        when(stateMapper.update(eq(null), any())).thenReturn(1);

        service.save(true, 2, null, "p-wao", List.of("待办"), "fw-auto");

        verify(stateMapper).update(eq(null), any());
    }

    private static TaskEntity task(String id, String title) {
        TaskEntity task = new TaskEntity();
        task.setId(id);
        task.setTitle(title);
        return task;
    }

    private static AiRunDto run(String id, TaskEntity task) {
        AiRunDto run = mock(AiRunDto.class);
        when(run.id()).thenReturn(id);
        when(run.taskId()).thenReturn(task.getId());
        when(run.taskTitle()).thenReturn(task.getTitle());
        return run;
    }
}
