package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.domain.SystemPeriodicReportEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.SaveSystemReportSettingsRequest;
import cn.wayuwa.wao.dto.SystemReportSettingsDto;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import cn.wayuwa.wao.mapper.SystemPeriodicReportMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SystemPeriodicReportServiceTest {

    @Test
    void resolvesClosedShanghaiDayWeekMonthAndRejectsOpenPeriod() {
        LocalDate today = LocalDate.of(2027, 1, 4);

        assertThat(SystemPeriodicReportService.resolvePeriod("DAY", null, today))
                .extracting("key", "start", "end")
                .containsExactly("2027-01-03", LocalDate.of(2027, 1, 3), LocalDate.of(2027, 1, 3));
        assertThat(SystemPeriodicReportService.resolvePeriod("WEEK", null, today))
                .extracting("key", "start", "end")
                .containsExactly("2026-W53", LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3));
        assertThat(SystemPeriodicReportService.resolvePeriod("MONTH", null, today))
                .extracting("key", "start", "end")
                .containsExactly("2026-12", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31));

        assertThatThrownBy(() -> SystemPeriodicReportService.resolvePeriod("DAY", today, today))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("已经结束");
    }

    @Test
    void aggregatesRealTaskEventsRunStatusesAndOnlyMeteredTokens() {
        SystemPeriodicReportMapper reports = mock(SystemPeriodicReportMapper.class);
        ProjectMapper projects = mock(ProjectMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        AiTaskRunMapper runs = mock(AiTaskRunMapper.class);
        SettingService settings = mock(SettingService.class);
        SystemPeriodicReportService service = new SystemPeriodicReportService(
                reports, projects, tasks, runs, settings, new ObjectMapper().findAndRegisterModules());
        LocalDate day = LocalDate.now(SystemPeriodicReportService.SHANGHAI).minusDays(2);

        ProjectEntity project = new ProjectEntity();
        project.setId("p1"); project.setName("WAO");
        TaskEntity task = new TaskEntity();
        task.setId("t1"); task.setTitle("系统报告"); task.setProjectId("p1"); task.setStatus("已完成");
        task.setCreatedAt(day.atTime(9, 0)); task.setCompletedAt(day.atTime(18, 0));
        AiTaskRunEntity success = run("ar1", day.atTime(10, 0), "succeeded", 120L);
        AiTaskRunEntity failed = run("ar2", day.atTime(11, 0), "failed", null);

        when(projects.selectList(null)).thenReturn(List.of(project));
        when(tasks.selectList(any())).thenReturn(List.of(task));
        when(runs.selectList(any())).thenReturn(List.of(success, failed));
        when(tasks.selectCount(any())).thenReturn(7L, 3L);
        when(runs.selectCount(null)).thenReturn(9L);

        var result = service.generate("DAY", day);

        assertThat(result.taskCreatedCount()).isEqualTo(1);
        assertThat(result.taskCompletedCount()).isEqualTo(1);
        assertThat(result.aiRunCount()).isEqualTo(2);
        assertThat(result.aiRunSuccessCount()).isEqualTo(1);
        assertThat(result.aiRunFailureCount()).isEqualTo(1);
        assertThat(result.aiRunMeteredCount()).isEqualTo(1);
        assertThat(result.totalTokens()).isEqualTo(120L);
        assertThat(result.aggregationOptions()).containsExactly("TASK_CREATED", "TASK_COMPLETED", "AI_RUN");
        assertThat(result.tasks()).singleElement().satisfies(item ->
                assertThat(item.events()).containsExactly("CREATED", "COMPLETED"));

        ArgumentCaptor<SystemPeriodicReportEntity> saved = ArgumentCaptor.forClass(SystemPeriodicReportEntity.class);
        verify(reports).upsert(saved.capture());
        assertThat(saved.getValue().getTaskSourcesJson()).contains("系统报告", "CREATED", "COMPLETED");
        assertThat(saved.getValue().getRunSourcesJson()).contains("succeeded", "failed");
    }

    @Test
    void configuredScheduleRefreshesSelectedPeriodsOnlyAtDueMinute() throws Exception {
        SystemPeriodicReportMapper reports = mock(SystemPeriodicReportMapper.class);
        ProjectMapper projects = mock(ProjectMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        AiTaskRunMapper runs = mock(AiTaskRunMapper.class);
        SettingService settings = mock(SettingService.class);
        SystemPeriodicReportService service = new SystemPeriodicReportService(
                reports, projects, tasks, runs, settings, new ObjectMapper().findAndRegisterModules());
        when(settings.get(SettingService.SYSTEM_REPORT_SETTINGS)).thenReturn(new ObjectMapper().writeValueAsString(
                new SystemReportSettingsDto(true, "08:30", 6, List.of("DAY", "MONTH"),
                        List.of("TASK_CREATED", "AI_RUN"))));
        when(projects.selectList(null)).thenReturn(List.of());
        when(tasks.selectList(any())).thenReturn(List.of());
        when(runs.selectList(any())).thenReturn(List.of());
        when(tasks.selectCount(any())).thenReturn(0L);
        when(runs.selectCount(null)).thenReturn(0L);

        assertThat(service.prepareScheduledReports(LocalDateTime.of(2026, 9, 1, 8, 29))).isFalse();
        verify(reports, times(0)).upsert(any());

        assertThat(service.prepareScheduledReports(LocalDateTime.of(2026, 9, 1, 8, 30))).isTrue();
        verify(reports, times(2)).upsert(any());

        assertThat(service.prepareScheduledReports(LocalDateTime.of(2026, 9, 1, 14, 30))).isTrue();
        verify(reports, times(4)).upsert(any());
    }

    @Test
    void settingsAreCanonicalPersistedAndInvalidOptionsAreRejected() {
        SystemPeriodicReportMapper reports = mock(SystemPeriodicReportMapper.class);
        ProjectMapper projects = mock(ProjectMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        AiTaskRunMapper runs = mock(AiTaskRunMapper.class);
        SettingService settings = mock(SettingService.class);
        SystemPeriodicReportService service = new SystemPeriodicReportService(
                reports, projects, tasks, runs, settings, new ObjectMapper().findAndRegisterModules());

        var saved = service.saveSettings(new SaveSystemReportSettingsRequest(
                true, "09:05", 3, List.of("MONTH", "DAY"), List.of("AI_RUN", "TASK_COMPLETED")));

        assertThat(saved.periods()).containsExactly("DAY", "MONTH");
        assertThat(saved.aggregationOptions()).containsExactly("TASK_COMPLETED", "AI_RUN");
        var payload = ArgumentCaptor.forClass(String.class);
        verify(settings).put(org.mockito.ArgumentMatchers.eq(SettingService.SYSTEM_REPORT_SETTINGS), payload.capture());
        assertThat(payload.getValue()).contains("09:05", "TASK_COMPLETED", "AI_RUN");
        assertThatThrownBy(() -> service.saveSettings(new SaveSystemReportSettingsRequest(
                true, "25:00", 2, List.of("DAY"), List.of("AI_RUN"))))
                .isInstanceOf(BadRequestException.class);

        when(settings.get(SettingService.SYSTEM_REPORT_SETTINGS)).thenReturn("{broken");
        assertThat(service.settings()).isEqualTo(new SystemReportSettingsDto(
                true, "00:00", 24, List.of("DAY", "WEEK", "MONTH"),
                List.of("TASK_CREATED", "TASK_COMPLETED", "AI_RUN")));
    }

    @Test
    void disabledAggregationDoesNotReadOrExposeUnselectedSources() {
        SystemPeriodicReportMapper reports = mock(SystemPeriodicReportMapper.class);
        ProjectMapper projects = mock(ProjectMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        AiTaskRunMapper runs = mock(AiTaskRunMapper.class);
        SettingService settings = mock(SettingService.class);
        var json = new ObjectMapper().findAndRegisterModules();
        when(settings.get(SettingService.SYSTEM_REPORT_SETTINGS)).thenReturn(write(json,
                new SystemReportSettingsDto(true, "00:00", 24, List.of("DAY"), List.of("TASK_COMPLETED"))));
        when(projects.selectList(null)).thenReturn(List.of());
        when(tasks.selectList(any())).thenReturn(List.of());
        when(tasks.selectCount(any())).thenReturn(0L);
        when(runs.selectCount(null)).thenReturn(4L);
        SystemPeriodicReportService service = new SystemPeriodicReportService(
                reports, projects, tasks, runs, settings, json);

        var result = service.generate("DAY", LocalDate.now(SystemPeriodicReportService.SHANGHAI).minusDays(2));

        assertThat(result.aggregationOptions()).containsExactly("TASK_COMPLETED");
        assertThat(result.runs()).isEmpty();
        verify(runs, times(0)).selectList(any());
    }

    private static String write(ObjectMapper json, Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }

    private static AiTaskRunEntity run(String id, LocalDateTime createdAt, String status, Long tokens) {
        AiTaskRunEntity run = new AiTaskRunEntity();
        run.setId(id); run.setTaskId("t1"); run.setProjectId("p1"); run.setEngine("codex");
        run.setCreatedAt(createdAt); run.setStatus(status); run.setTotalTokens(tokens);
        return run;
    }
}
