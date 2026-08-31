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
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.AssistantStatusDto;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DailyGuidanceServiceTest {

    private final MemberPreferenceMapper preferenceMapper = mock(MemberPreferenceMapper.class);
    private final MemberMapper memberMapper = mock(MemberMapper.class);
    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final AiAssistantService assistant = mock(AiAssistantService.class);
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final DailyGuidanceService service = new DailyGuidanceService(
            preferenceMapper, memberMapper, taskMapper, projectMapper, assistant, json);

    @Test
    void returnsTodayCachedGuidanceWithoutCallingCodex() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        when(preferenceMapper.find("wg", DailyGuidanceService.PREFERENCE_KEY)).thenReturn(
                "{\"date\":\"" + today + "\",\"content\":\"保持好奇，先听后说\","
                        + "\"generatedAt\":\"2026-08-13T08:00:00\",\"focus\":[]}");

        var result = service.today("wg");

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.content()).contains("先听后说");
        verify(assistant, never()).status();
    }

    @Test
    void generatesGrowthCoachFromActiveAndRecentCompletedTasksAndCachesIt() {
        when(preferenceMapper.find("wg", DailyGuidanceService.PREFERENCE_KEY)).thenReturn(null);
        when(preferenceMapper.find("wg", WorkdayRhythmDashboardService.DAY_KEY_PREFIX
                + LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))))
                .thenReturn("{\"focus\":[\"完成真实环境发布\",\"\",\"整理用户反馈\"]}");
        when(assistant.status()).thenReturn(new AssistantStatusDto(true, "已连接本地节点"));
        when(taskMapper.selectList(any())).thenReturn(List.of(
                task(), completedTask("复盘一次架构返工", 2), completedTask("过期的旧任务", 40)));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project()));
        when(assistant.dailyGuidanceAnswer(contains("L1 写代码"), eq("wg"), contains("明天我就要去上班了")))
                .thenReturn("## 今天看到的成长信号\n\n复盘一次架构返工。\n\n"
                        + "## 换个角度\n\n从实现走向判断。\n\n"
                        + "## 问问自己\n\n> 哪个判断避免了返工？\n\n"
                        + "## 今天做个小实验\n\n记录一个判断及结果。");

        var result = service.generate("wg", false);

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.content()).contains("哪个判断避免了返工");
        ArgumentCaptor<String> context = ArgumentCaptor.forClass(String.class);
        verify(assistant).dailyGuidanceAnswer(any(), eq("wg"), context.capture());
        assertThat(context.getValue())
                .contains("## 今天已保存的重点", "完成真实环境发布", "整理用户反馈",
                        "## 正在进行", "准备去新公司上班", "## 最近 30 天完成", "复盘一次架构返工")
                .doesNotContain("过期的旧任务");
        verify(preferenceMapper).upsert(eq("wg"), eq(DailyGuidanceService.PREFERENCE_KEY),
                contains("完成真实环境发布"));
    }

    @Test
    void invalidatesTodayGuidanceWhenSavedFocusChanges() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        when(preferenceMapper.find("wg", DailyGuidanceService.PREFERENCE_KEY)).thenReturn(
                "{\"date\":\"" + today + "\",\"content\":\"围绕旧重点生成\","
                        + "\"generatedAt\":\"2026-08-31T08:00:00\",\"focus\":[\"旧重点\"]}");
        when(preferenceMapper.find("wg", WorkdayRhythmDashboardService.DAY_KEY_PREFIX + today))
                .thenReturn("{\"focus\":[\"新的今日重点\"]}");
        when(assistant.status()).thenReturn(new AssistantStatusDto(true, "已连接本地节点"));

        var result = service.today("wg");

        assertThat(result.status()).isEqualTo("missing");
        assertThat(result.content()).isNull();
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

    @Test
    void warmupPreparesOnlyMissingMembersAndContinuesAfterOneFailure() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        when(memberMapper.selectList(null)).thenReturn(List.of(member("cached"), member("failed"), member("ready")));
        when(preferenceMapper.find("cached", DailyGuidanceService.PREFERENCE_KEY)).thenReturn(
                "{\"date\":\"" + today + "\",\"content\":\"已有建议\","
                        + "\"generatedAt\":\"2026-08-13T08:00:00\",\"focus\":[]}");
        when(assistant.status()).thenReturn(new AssistantStatusDto(true, "已连接本地节点"));
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(assistant.dailyGuidanceAnswer(any(), eq("failed"), any()))
                .thenThrow(new RuntimeException("本地 Codex 暂时繁忙"));
        when(assistant.dailyGuidanceAnswer(any(), eq("ready"), any())).thenReturn("## 今日行动\n\n- 先做一件事");

        var result = service.prepareMissingForAllMembers();

        assertThat(result.members()).isEqualTo(3);
        assertThat(result.cached()).isEqualTo(1);
        assertThat(result.generated()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.lastError()).contains("暂时繁忙");
        verify(assistant, never()).dailyGuidanceAnswer(any(), eq("cached"), any());
        verify(preferenceMapper).upsert(eq("ready"), eq(DailyGuidanceService.PREFERENCE_KEY), contains("今日行动"));
    }

    @Test
    void warmupDefersAllMissingMembersWhileCodexIsOffline() {
        when(memberMapper.selectList(null)).thenReturn(List.of(member("wg"), member("wd")));
        when(assistant.status()).thenReturn(new AssistantStatusDto(false, "未检测到在线的本地 Codex"));

        var result = service.prepareMissingForAllMembers();

        assertThat(result.generated()).isZero();
        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.lastError()).contains("未检测到在线");
        verify(assistant, never()).dailyGuidanceAnswer(any(), any(), any());
    }

    private MemberEntity member(String id) {
        MemberEntity member = new MemberEntity();
        member.setId(id);
        return member;
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

    private TaskEntity completedTask(String title, int daysAgo) {
        TaskEntity task = new TaskEntity();
        task.setId("done-" + daysAgo);
        task.setTitle(title);
        task.setDescription("记录当时做出的判断、遇到的风险和最后结果");
        task.setStatus(TaskService.DONE);
        task.setCompletedAt(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(daysAgo));
        task.setOwnerId("wg");
        task.setProjectId("p1");
        return task;
    }
}
