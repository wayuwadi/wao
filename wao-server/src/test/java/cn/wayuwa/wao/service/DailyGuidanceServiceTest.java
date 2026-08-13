package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.AssistantStatusDto;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

class DailyGuidanceServiceTest {

    private final MemberPreferenceMapper preferenceMapper = mock(MemberPreferenceMapper.class);
    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final AiAssistantService assistant = mock(AiAssistantService.class);
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final DailyGuidanceService service = new DailyGuidanceService(
            preferenceMapper, taskMapper, projectMapper, assistant, json);

    @Test
    void returnsTodayCachedGuidanceWithoutCallingCodex() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        when(preferenceMapper.find("wg", DailyGuidanceService.PREFERENCE_KEY)).thenReturn(
                "{\"date\":\"" + today + "\",\"content\":\"保持好奇，先听后说\","
                        + "\"generatedAt\":\"2026-08-13T08:00:00\"}");

        var result = service.today("wg");

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.content()).contains("先听后说");
        verify(assistant, never()).status();
    }

    @Test
    void generatesGuidanceFromRealOwnedTasksAndCachesIt() {
        when(preferenceMapper.find("wg", DailyGuidanceService.PREFERENCE_KEY)).thenReturn(null);
        when(assistant.status()).thenReturn(new AssistantStatusDto(true, "已连接本地节点"));
        when(taskMapper.selectList(any())).thenReturn(List.of(task()));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project()));
        when(assistant.dailyGuidanceAnswer(contains("此刻建议"), eq("wg"), contains("明天我就要去上班了")))
                .thenReturn("## 先观察，再行动\n\n- 主动记住同事名字");

        var result = service.generate("wg", false);

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.content()).contains("主动记住同事名字");
        verify(preferenceMapper).upsert(eq("wg"), eq(DailyGuidanceService.PREFERENCE_KEY), contains("先观察"));
    }

    @Test
    void concurrentInitialLoadsGenerateOnceForSameMember() throws Exception {
        AtomicReference<String> stored = new AtomicReference<>();
        when(preferenceMapper.find("wg", DailyGuidanceService.PREFERENCE_KEY))
                .thenAnswer(ignored -> stored.get());
        org.mockito.Mockito.doAnswer(invocation -> {
            stored.set(invocation.getArgument(2));
            return null;
        }).when(preferenceMapper).upsert(eq("wg"), eq(DailyGuidanceService.PREFERENCE_KEY), any());
        when(assistant.status()).thenReturn(new AssistantStatusDto(true, "已连接本地节点"));
        when(taskMapper.selectList(any())).thenReturn(List.of(task()));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project()));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(assistant.dailyGuidanceAnswer(any(), eq("wg"), any())).thenAnswer(ignored -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return "## 先认识团队";
        });

        CompletableFuture<?> first = CompletableFuture.supplyAsync(() -> service.generate("wg", false));
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<?> second = CompletableFuture.supplyAsync(() -> service.generate("wg", false));
        release.countDown();
        CompletableFuture.allOf(first, second).get(3, TimeUnit.SECONDS);

        verify(assistant, times(1)).dailyGuidanceAnswer(any(), eq("wg"), any());
        verify(preferenceMapper, times(1)).upsert(eq("wg"), eq(DailyGuidanceService.PREFERENCE_KEY), any());
    }

    private TaskEntity task() {
        TaskEntity task = new TaskEntity();
        task.setId("t1");
        task.setTitle("准备去新公司上班");
        task.setDescription("明天我就要去上班了，希望知道怎样快速融入团队、发挥得更好");
        task.setStatus("AI 进行中");
        task.setDueDate(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString());
        task.setOwnerId("wg");
        task.setProjectId("p1");
        return task;
    }

    private ProjectEntity project() {
        ProjectEntity project = new ProjectEntity();
        project.setId("p1");
        project.setName("个人成长");
        return project;
    }
}
