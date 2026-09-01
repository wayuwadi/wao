package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.AutomationJobEntity;
import cn.wayuwa.wao.dto.AutomationJobDto;
import cn.wayuwa.wao.dto.CreateJobRequest;
import cn.wayuwa.wao.dto.MemberDto;
import cn.wayuwa.wao.mapper.AutomationJobMapper;
import cn.wayuwa.wao.mapper.AutomationRunMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AutomationServiceTest {

    private final AutomationJobMapper jobMapper = mock(AutomationJobMapper.class);
    private final AutomationRunMapper runMapper = mock(AutomationRunMapper.class);
    private final MemberService memberService = mock(MemberService.class);
    private final AutomationService service =
            new AutomationService(jobMapper, runMapper, new ObjectMapper(), List.of(), memberService);

    private AutomationJobEntity job(String status) {
        AutomationJobEntity e = new AutomationJobEntity();
        e.setId("j1");
        e.setName("测试任务");
        e.setStatus(status);
        e.setStepsJson("[\"步骤一\",\"步骤二\"]");
        return e;
    }

    @Test
    void createJobDefaultsToNormalStatusAndNoCron() {
        when(jobMapper.selectCount(null)).thenReturn(0L);

        AutomationJobDto dto = service.createJob(
                new CreateJobRequest("新任务", null, null, "描述", "来源", "目标", List.of("步骤一"), null, null));

        assertThat(dto.id()).startsWith("j");
        assertThat(dto.status()).isEqualTo("正常");
        assertThat(dto.last()).isEqualTo("尚未运行");
    }

    @Test
    void createJobRejectsInvalidCron() {
        when(jobMapper.selectCount(null)).thenReturn(0L);

        assertThatThrownBy(() -> service.createJob(
                new CreateJobRequest("新任务", null, null, null, null, null, List.of(), "not-a-cron", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runJobOnBrokenStatusFailsAndKeepsStatus() {
        AutomationJobEntity e = job("待修复");
        when(jobMapper.selectById("j1")).thenReturn(e);
        when(runMapper.selectList(any())).thenReturn(List.of());

        AutomationJobDto dto = service.runJob("j1", "wg");

        assertThat(dto.status()).isEqualTo("待修复");
        assertThat(dto.last()).contains("失败");
    }

    @Test
    void runJobWithoutRealHandlerFailsClosed() {
        AutomationJobEntity e = job("正常");
        when(jobMapper.selectById("j1")).thenReturn(e);
        when(runMapper.selectList(any())).thenReturn(List.of());

        AutomationJobDto dto = service.runJob("j1", "wg");

        assertThat(dto.status()).isEqualTo("正常");
        assertThat(dto.last()).contains("失败 · 未配置真实执行器");

        ArgumentCaptor<cn.wayuwa.wao.domain.AutomationRunEntity> saved = ArgumentCaptor.captor();
        verify(runMapper).insert(saved.capture());
        assertThat(saved.getValue().getRunAt()).isNotNull();
        assertThat(saved.getValue().getRunTimeLabel()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
        assertThat(saved.getValue().getDurationMs()).isZero();
    }

    @Test
    void historicalRunUsesItsActualDateInsteadOfPermanentTodayLabel() {
        AutomationJobEntity e = job("正常");
        var run = new cn.wayuwa.wao.domain.AutomationRunEntity();
        LocalDateTime runAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusDays(3).withSecond(0).withNano(0);
        run.setRunAt(runAt);
        run.setRunTimeLabel("今天 23:59");
        run.setResult("成功");
        when(jobMapper.selectById("j1")).thenReturn(e);
        when(runMapper.selectList(any())).thenReturn(List.of(run));

        AutomationJobDto dto = service.getJob("j1");

        assertThat(dto.runs().get(0).time())
                .isEqualTo(runAt.getMonthValue() + "月" + runAt.getDayOfMonth() + "日 "
                        + String.format("%02d:%02d", runAt.getHour(), runAt.getMinute()));
        assertThat(dto.last()).startsWith(dto.runs().get(0).time()).doesNotStartWith("今天");
    }

    @Test
    void listJobsLoadsMemberNamesOnceForAllHistoricalRuns() {
        AutomationJobEntity e = job("正常");
        var first = new cn.wayuwa.wao.domain.AutomationRunEntity();
        first.setJobId("j1"); first.setExecutorId("m1"); first.setResult("成功"); first.setOrd(1);
        var second = new cn.wayuwa.wao.domain.AutomationRunEntity();
        second.setJobId("j1"); second.setExecutorId("m2"); second.setResult("成功"); second.setOrd(2);
        when(jobMapper.selectList(any())).thenReturn(List.of(e));
        when(runMapper.selectList(any())).thenReturn(List.of(first, second));
        when(memberService.list()).thenReturn(List.of(
                new MemberDto("m1", "娃哥", "娃", "#fff", "成员"),
                new MemberDto("m2", "娃弟", "弟", "#000", "成员")));

        AutomationJobDto dto = service.listJobs().get(0);

        assertThat(dto.runs()).extracting(run -> run.executor()).containsExactly("娃哥", "娃弟");
        verify(memberService).list();
    }

    @Test
    void delayedScheduledRunKeepsItsPlannedDateAndAdvancesFromThatPlan() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AutomationJobEntity.class);
        AutomationJobEntity e = job("正常");
        e.setCronExpr("0 59 23 * * *");
        e.setNextFireAt(LocalDateTime.of(2026, 8, 4, 23, 59));
        e.setHandler("planned");
        when(jobMapper.update(any(), any())).thenReturn(1);
        when(runMapper.selectList(any())).thenReturn(List.of());
        AtomicReference<LocalDateTime> received = new AtomicReference<>();
        JobHandler planned = new JobHandler() {
            @Override public String name() { return "planned"; }
            @Override public String execute() { return "不应走无计划时间入口"; }
            @Override public String execute(AutomationJobEntity job, LocalDateTime scheduledFireAt) {
                received.set(scheduledFireAt);
                return "成功";
            }
        };
        AutomationService withHandler = new AutomationService(jobMapper, runMapper, new ObjectMapper(),
                List.of(planned), mock(MemberService.class));

        withHandler.executeScheduled(e);

        assertThat(received.get()).isEqualTo(LocalDateTime.of(2026, 8, 4, 23, 59));
        assertThat(e.getNextFireAt()).isEqualTo(LocalDateTime.of(2026, 8, 5, 23, 59));
    }

    @Test
    void pauseThenResumeRestoresNormalStatus() {
        AutomationJobEntity e = job("正常");
        when(jobMapper.selectById("j1")).thenReturn(e);
        when(runMapper.selectList(any())).thenReturn(List.of());

        service.pauseJob("j1");
        assertThat(e.getStatus()).isEqualTo("已暂停");

        AutomationJobDto resumed = service.resumeJob("j1");
        assertThat(resumed.status()).isEqualTo("正常");
    }

    @Test
    void reauthRestoresNormalStatus() {
        AutomationJobEntity e = job("待修复");
        when(jobMapper.selectById("j1")).thenReturn(e);
        when(runMapper.selectList(any())).thenReturn(List.of());

        AutomationJobDto dto = service.reauthJob("j1");

        assertThat(dto.status()).isEqualTo("正常");
    }

    @Test
    void runJobWithHandlerRecordsHandlerResult() {
        AutomationJobEntity e = job("正常");
        e.setHandler("demo");
        when(jobMapper.selectById("j1")).thenReturn(e);
        when(runMapper.selectList(any())).thenReturn(List.of());
        AutomationService withHandler = new AutomationService(jobMapper, runMapper, new ObjectMapper(),
                List.of(handler("demo", () -> "成功 · 共 3 条消息")), mock(MemberService.class));

        AutomationJobDto dto = withHandler.runJob("j1", "wg");

        assertThat(dto.last()).contains("成功 · 共 3 条消息");
        ArgumentCaptor<cn.wayuwa.wao.domain.AutomationRunEntity> saved = ArgumentCaptor.captor();
        verify(runMapper).insert(saved.capture());
        assertThat(saved.getValue().getDurationMs()).isNotNegative();
    }

    @Test
    void runJobPersistsStructuredAiRunLink() {
        AutomationJobEntity e = job("正常");
        e.setHandler("inspection");
        when(jobMapper.selectById("j1")).thenReturn(e);
        when(runMapper.selectList(any())).thenReturn(List.of());
        JobHandler inspection = new JobHandler() {
            @Override public String name() { return "inspection"; }
            @Override public String execute() { return "兼容入口"; }
            @Override public JobExecutionResult executeWithMetadata(
                    AutomationJobEntity job, LocalDateTime scheduledFireAt) {
                return new JobExecutionResult("成功 · 已创建 AI Run ar-inspection", "ar-inspection");
            }
        };
        AutomationService withHandler = new AutomationService(jobMapper, runMapper, new ObjectMapper(),
                List.of(inspection), mock(MemberService.class));

        withHandler.runJob("j1", "wg");

        ArgumentCaptor<cn.wayuwa.wao.domain.AutomationRunEntity> saved = ArgumentCaptor.captor();
        verify(runMapper).insert(saved.capture());
        assertThat(saved.getValue().getAiRunId()).isEqualTo("ar-inspection");
    }

    @Test
    void runJobWithFailingHandlerRecordsFailureInsteadOfThrowing() {
        AutomationJobEntity e = job("正常");
        e.setHandler("demo");
        when(jobMapper.selectById("j1")).thenReturn(e);
        when(runMapper.selectList(any())).thenReturn(List.of());
        AutomationService withHandler = new AutomationService(jobMapper, runMapper, new ObjectMapper(),
                List.of(handler("demo", () -> {
                    throw new IllegalStateException("请先选择要同步的群聊");
                })), mock(MemberService.class));

        AutomationJobDto dto = withHandler.runJob("j1", "wg");

        assertThat(dto.last()).contains("失败 · 请先选择要同步的群聊");
    }

    private JobHandler handler(String name, java.util.function.Supplier<String> body) {
        return new JobHandler() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String execute() {
                return body.get();
            }
        };
    }

    @Test
    void getJobThrowsWhenMissing() {
        when(jobMapper.selectById("nope")).thenReturn(null);
        assertThatThrownBy(() -> service.getJob("nope")).isInstanceOf(NotFoundException.class);
    }
}
