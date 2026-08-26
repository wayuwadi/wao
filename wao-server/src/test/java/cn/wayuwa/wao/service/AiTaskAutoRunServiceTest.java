package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        state.setProjectId("p-wao");
        state.setTaskStatusesJson("[\"待办\"]");
        state.setNotificationWebhookId("fw-auto");
        project = new ProjectEntity();
        project.setId("p-wao");
        project.setName("Wa All in One");
        when(stateMapper.selectById("oldest-task")).thenReturn(state);
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(projectMapper.selectById("p-wao")).thenReturn(project);
        when(runs.getConfig("p-wao")).thenReturn(new AiProjectConfigDto(
                "p-wao", true, "https://github.com/example/wao.git", "main", "wao", "codex", null, 2));
    }

    @Test
    void overviewExplainsWhenNoRunnableTaskExists() {
        AiTaskAutoRunPolicyDto overview = service.overview();

        assertThat(overview.enabled()).isTrue();
        assertThat(overview.idleHours()).isEqualTo(2);
        assertThat(overview.projectId()).isEqualTo("p-wao");
        assertThat(overview.taskStatuses()).containsExactly("待办");
        assertThat(overview.notificationWebhookId()).isEqualTo("fw-auto");
        assertThat(overview.status()).isEqualTo("no_task");
        assertThat(overview.message()).contains("Wa All in One", "待办");
    }

    @Test
    void dueCheckLaunchesOldestCandidateWithPlanMode() {
        TaskEntity task = new TaskEntity();
        task.setId("t-oldest");
        task.setTitle("最早录入的待办");
        AiRunDto run = mock(AiRunDto.class);
        when(run.id()).thenReturn("ar-auto");
        when(stateMapper.claimDueCheck()).thenReturn(1);
        when(taskMapper.selectOldestAutoRunCandidate("p-wao", List.of("待办"))).thenReturn(task);
        when(runs.enqueue(eq(List.of("t-oldest")), eq(null), eq(null), eq(null), eq(null),
                eq(null), eq(null), eq(true), eq(AiRunService.IDLE_AUTO_RUN_REQUESTED_BY),
                eq("https://wao.example")))
                .thenReturn(List.of(run));
        when(notifier.notifyIdleAutoRun("t-oldest", "p-wao", "最早录入的待办",
                "Wa All in One", 2, "fw-auto")).thenReturn(true);

        service.checkAndLaunch();

        verify(runs).enqueue(eq(List.of("t-oldest")), eq(null), eq(null), eq(null), eq(null),
                eq(null), eq(null), eq(true), eq(AiRunService.IDLE_AUTO_RUN_REQUESTED_BY),
                eq("https://wao.example"));
        verify(notifier).notifyIdleAutoRun("t-oldest", "p-wao", "最早录入的待办",
                "Wa All in One", 2, "fw-auto");
        verify(stateMapper).update(eq(null), any());
    }

    @Test
    void aNodeThatLosesTheDatabaseClaimDoesNothing() {
        when(stateMapper.claimDueCheck()).thenReturn(0);

        service.checkAndLaunch();

        verify(taskMapper, never()).selectOldestAutoRunCandidate(any(), anyList());
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
                service.save(true, 2, "p-wao", List.of("已完成"), "fw-auto"))
                .isInstanceOf(cn.wayuwa.wao.common.BadRequestException.class)
                .hasMessageContaining("只支持待办、收集中或进行中");
    }
}
