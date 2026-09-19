package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.domain.AiTaskRunTaskEntity;
import cn.wayuwa.wao.domain.AiQuotaSnapshotEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.AiQuotaHistoryDto;
import cn.wayuwa.wao.dto.AiRunTrendItem;
import cn.wayuwa.wao.dto.AiUsageInsightsDto;
import cn.wayuwa.wao.mapper.AiQuotaSnapshotMapper;
import cn.wayuwa.wao.mapper.AiRunnerNodeMapper;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import org.junit.jupiter.api.Test;

class DashboardServiceTest {
    @org.junit.jupiter.api.BeforeAll
    static void initializeMapperMetadata() {
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, AiTaskRunEntity.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, TaskEntity.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, cn.wayuwa.wao.domain.AiRunnerNodeEntity.class);
    }


    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final AiTaskRunMapper runMapper = mock(AiTaskRunMapper.class);
    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final AiQuotaSnapshotMapper quotaMapper = mock(AiQuotaSnapshotMapper.class);
    private final AiRunnerNodeMapper runnerMapper = mock(AiRunnerNodeMapper.class);
    private final DashboardService service = new DashboardService(
            runMapper, taskMapper, runnerMapper, quotaMapper);

    @Test
    void summaryUsesScalarCountsWithoutLoadingHistory() {
        when(runMapper.selectDashboardCounts(any())).thenReturn(
                new cn.wayuwa.wao.domain.DashboardRunCounts(200, 3, 17, 2));
        when(runnerMapper.selectCount(any())).thenReturn(2L);
        when(taskMapper.selectCount(any())).thenReturn(8L);
        assertThat(service.summary()).isEqualTo(new cn.wayuwa.wao.dto.DashboardSummaryDto(200, 3, 2, 8, 17, 2));
        org.mockito.Mockito.verify(runMapper, org.mockito.Mockito.never()).selectList(any());
        org.mockito.Mockito.verify(taskMapper, org.mockito.Mockito.never()).selectList(any());
        org.mockito.Mockito.verify(runnerMapper, org.mockito.Mockito.never()).selectList(any());
    }

    @Test
    void trendAndInsightsDoNotSelectUnrelatedPayloadColumns() {
        when(runMapper.selectList(any())).thenReturn(List.of());
        service.aiRunTrend(30);
        service.usageInsights(30);
        var queries = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        org.mockito.Mockito.verify(runMapper, org.mockito.Mockito.times(2)).selectList(queries.capture());
        assertThat(queries.getAllValues()).allSatisfy(query -> {
            assertThat(query.getSqlSelect()).contains("status", "total_tokens");
            assertThat(query.getSqlSelect()).doesNotContain("result_summary", "latest_progress", "pending_input", "capability_");
        });
    }

    @Test
    void aiRunTrendAggregatesDailyFrequencyAndRealTokenUsage() {
        LocalDate today = LocalDate.now(ZONE);
        AiTaskRunEntity recorded = run(today.atTime(9, 0), "succeeded");
        recorded.setModel("gpt-5-codex");
        recorded.setInputTokens(1_200L);
        recorded.setOutputTokens(300L);
        recorded.setCachedInputTokens(8_500L);
        recorded.setTotalTokens(10_000L);

        AiTaskRunEntity costOnly = run(today.atTime(12, 0), "failed");
        costOnly.setModel("claude-opus-5");
        costOnly.setCostUsd(new BigDecimal("0.120000"));

        AiTaskRunEntity unreported = run(today.minusDays(1).atTime(23, 0), "cancelled");
        when(runMapper.selectList(any())).thenReturn(List.of(recorded, costOnly, unreported));

        List<AiRunTrendItem> trend = service.aiRunTrend(2);

        assertThat(trend).hasSize(2);
        assertThat(trend.get(0).date()).isEqualTo(today.minusDays(1).toString());
        assertThat(trend.get(0).total()).isEqualTo(1);
        assertThat(trend.get(0).cancelled()).isEqualTo(1);
        assertThat(trend.get(0).recordedRuns()).isZero();
        assertThat(trend.get(0).totalTokens()).isZero();

        AiRunTrendItem current = trend.get(1);
        assertThat(current.date()).isEqualTo(today.toString());
        assertThat(current.total()).isEqualTo(2);
        assertThat(current.succeeded()).isEqualTo(1);
        assertThat(current.failed()).isEqualTo(1);
        // 只有模型和金额并不代表拿到了 Token，不能用它抬高 Token 记账覆盖率。
        assertThat(current.recordedRuns()).isEqualTo(1);
        assertThat(current.inputTokens()).isEqualTo(1_200L);
        assertThat(current.outputTokens()).isEqualTo(300L);
        assertThat(current.cachedInputTokens()).isEqualTo(8_500L);
        assertThat(current.totalTokens()).isEqualTo(10_000L);
    }

    @Test
    void aiRunTrendKeepsEmptyDaysAndClampsRequestedRange() {
        when(runMapper.selectList(any())).thenReturn(List.of());

        List<AiRunTrendItem> trend = service.aiRunTrend(0);

        assertThat(trend).hasSize(1);
        assertThat(trend.get(0).total()).isZero();
        assertThat(trend.get(0).recordedRuns()).isZero();
        assertThat(trend.get(0).totalTokens()).isZero();
    }

    @Test
    void quotaHistoryKeepsDailyClosingValueAndSeparatesRefreshFromDecrease() {
        LocalDate today = LocalDate.now(ZONE);
        when(quotaMapper.selectList(any())).thenReturn(List.of(
                quota(today.minusDays(2).atTime(9, 0), 80),
                quota(today.minusDays(1).atTime(9, 0), 65),
                quota(today.minusDays(1).atTime(18, 0), 60),
                quota(today.atTime(8, 0), 100)));

        AiQuotaHistoryDto history = service.quotaHistory(30);

        assertThat(history.recordedSamples()).isEqualTo(4);
        assertThat(history.series()).singleElement().satisfies(series -> {
            assertThat(series.points()).extracting(AiQuotaHistoryDto.DailyPoint::remainingPercent)
                    .containsExactly(80, 60, 100);
            assertThat(series.points()).extracting(AiQuotaHistoryDto.DailyPoint::decreasedPercent)
                    .containsExactly(0, 20, 0);
        });
        assertThat(history.refreshes()).singleElement().satisfies(refresh -> {
            assertThat(refresh.beforeRemainingPercent()).isEqualTo(60);
            assertThat(refresh.afterRemainingPercent()).isEqualTo(100);
            assertThat(refresh.refreshedPercent()).isEqualTo(40);
        });
        assertThat(history.officialResets()).isEmpty();
    }

    @Test
    void quotaHistoryUsesSupplierWindowAdvanceForExactOfficialResetInsteadOfBalanceIncrease() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDateTime officialReset = today.atTime(8, 37);
        when(quotaMapper.selectList(any())).thenReturn(List.of(
                quota(today.minusDays(1).atTime(10, 0), 50, officialReset),
                // 重置时间未推进时的上涨可能来自刷新卡，只进入普通上涨记录。
                quota(today.atTime(8, 20), 80, officialReset),
                // 即使重置后已经发生消耗，只要供应商周期推进，仍能确认官方重置。
                quota(today.atTime(8, 40), 70, officialReset.plusDays(7))));

        AiQuotaHistoryDto history = service.quotaHistory(30);

        assertThat(history.recordedSamples()).isEqualTo(3);
        assertThat(history.refreshes()).singleElement().satisfies(refresh ->
                assertThat(refresh.observedAt()).isEqualTo(today.atTime(8, 20).toString()));
        assertThat(history.officialResets()).singleElement().satisfies(reset -> {
            assertThat(reset.resetAt()).isEqualTo(officialReset.toString());
            assertThat(reset.confirmedAt()).isEqualTo(today.atTime(8, 40).toString());
            assertThat(reset.beforeRemainingPercent()).isEqualTo(80);
            assertThat(reset.afterRemainingPercent()).isEqualTo(70);
        });
    }

    @Test
    void usageInsightsMeasuresDeliveredOutcomesAndOnlyComparesCompleteSameTypeSamples() {
        LocalDate today = LocalDate.now(ZONE);
        AiTaskRunEntity first = analyzedRun("r1", "t1", today.minusDays(5).atTime(9, 0),
                "gpt-5.6-sol", "high", 1_800, 300, 10_000, true);
        AiTaskRunEntity second = analyzedRun("r2", "t2", today.minusDays(4).atTime(9, 0),
                "gpt-5.6-sol", "high", 2_400, 300, 10_000, true);
        AiTaskRunEntity third = analyzedRun("r3", "t3", today.minusDays(3).atTime(9, 0),
                "gpt-5.6-sol", "high", 3_000, 300, 10_000, true);
        AiTaskRunEntity fourth = analyzedRun("r4", "t4", today.minusDays(2).atTime(9, 0),
                "gpt-5.6-luna", "low", 600, 200, 8_000, true);
        AiTaskRunEntity fifth = analyzedRun("r5", "t5", today.minusDays(1).atTime(9, 0),
                "gpt-5.6-luna", "low", 660, 200, 8_000, true);
        AiTaskRunEntity failed = analyzedRun("r6", "t6", today.atTime(9, 0),
                "gpt-5.6-luna", "low", 720, 200, 8_000, false);
        failed.setRetryOfRunId("older-failure");
        when(runMapper.selectList(any())).thenReturn(List.of(first, second, third, fourth, fifth, failed));
        when(taskMapper.selectList(any())).thenReturn(List.of(
                task("t1", "已完成"), task("t2", "已完成"), task("t3", "待验证"),
                task("t4", "待验证"), task("t5", "待验证")));

        AiUsageInsightsDto insights = service.usageInsights(30);

        assertThat(insights.completedRuns()).isEqualTo(6);
        assertThat(insights.timedRuns()).isEqualTo(6);
        assertThat(insights.deliveredRuns()).isEqualTo(5);
        assertThat(insights.deliveredTasks()).isEqualTo(5);
        assertThat(insights.verifiedTasks()).isEqualTo(2);
        assertThat(insights.totalTokens()).isEqualTo(54_000);
        assertThat(insights.failedTokens()).isEqualTo(8_000);
        assertThat(insights.tokensPerDeliveredTask()).isEqualTo(10_800);
        assertThat(insights.retryRuns()).isEqualTo(1);
        assertThat(insights.retryTokens()).isEqualTo(8_000);
        assertThat(insights.medianDurationSeconds()).isEqualTo(1_800);
        assertThat(insights.medianAiExecutionSeconds()).isEqualTo(300);
        assertThat(insights.advice()).extracting(AiUsageInsightsDto.Advice::category)
                .containsExactly("OUTCOME", "QUALITY", "ROUTING", "RETRY", "ACCOUNTING");
        assertThat(insights.advice()).allMatch(AiUsageInsightsDto.Advice::sufficientData);
        assertThat(insights.advice().get(2).recommendation())
                .contains("功能开发", "gpt-5.6-sol · high", "gpt-5.6-luna · low");
        assertThat(insights.advice().get(2).evidence()).contains("AI 5.0 分钟", "AI 3.3 分钟");
    }

    @Test
    void usageInsightsCountsMergedTasksButKeepsUnitCostUnknownWhenMeteringIsIncomplete() {
        LocalDateTime now = LocalDate.now(ZONE).atTime(9, 0);
        AiTaskRunEntity delivered = analyzedRun("r1", "t1", now.minusDays(1),
                "gpt-5.6-sol", "high", 600, 300, 10_000, true);
        AiTaskRunEntity unmeteredFailure = analyzedRun("r2", "t3", now,
                "gpt-5.6-sol", "high", 600, 300, 1, false);
        unmeteredFailure.setTotalTokens(null);
        when(runMapper.selectList(any())).thenReturn(List.of(delivered, unmeteredFailure));
        when(runMapper.selectTaskLinks(any())).thenReturn(List.of(
                runTask("r1", "t1", 0), runTask("r1", "t2", 1), runTask("r2", "t3", 0)));

        AiUsageInsightsDto insights = service.usageInsights(30);

        assertThat(insights.deliveredRuns()).isEqualTo(1);
        assertThat(insights.deliveredTasks()).isEqualTo(2);
        assertThat(insights.meteredRuns()).isEqualTo(1);
        assertThat(insights.tokensPerDeliveredTask()).isNull();
        assertThat(insights.advice().get(0).sufficientData()).isFalse();
        assertThat(insights.advice().get(0).recommendation()).contains("补齐");
    }

    private static AiQuotaSnapshotEntity quota(LocalDateTime at, int remaining) {
        return quota(at, remaining, null);
    }

    private static AiQuotaSnapshotEntity quota(LocalDateTime at, int remaining, LocalDateTime resetsAt) {
        AiQuotaSnapshotEntity value = new AiQuotaSnapshotEntity();
        value.setSourceKey("central:aa1");
        value.setAccountLabel("Codex 主账号");
        value.setProvider("codex");
        value.setWindowKey("weekly");
        value.setWindowLabel("七天额度");
        value.setRemainingPercent(remaining);
        value.setResetsAt(resetsAt);
        value.setObservedAt(at);
        return value;
    }

    private static AiTaskRunEntity analyzedRun(String id, String taskId, LocalDateTime createdAt,
            String model, String effort, long durationSeconds, long aiDurationSeconds, long tokens,
            boolean succeeded) {
        AiTaskRunEntity run = run(createdAt, succeeded ? "succeeded" : "failed");
        run.setId(id);
        run.setTaskId(taskId);
        run.setModel(model);
        run.setReasoningEffort(effort);
        run.setActivityType("FEATURE_DEVELOPMENT");
        run.setDeliveryStage(succeeded ? "deployment_succeeded" : "code_failed");
        run.setTotalTokens(tokens);
        run.setAiExecutionDurationMs(aiDurationSeconds * 1_000);
        run.setStartedAt(createdAt);
        run.setFinishedAt(createdAt.plusSeconds(durationSeconds));
        return run;
    }

    private static TaskEntity task(String id, String status) {
        TaskEntity task = new TaskEntity();
        task.setId(id);
        task.setStatus(status);
        return task;
    }

    private static AiTaskRunTaskEntity runTask(String runId, String taskId, int order) {
        AiTaskRunTaskEntity link = new AiTaskRunTaskEntity();
        link.setRunId(runId);
        link.setTaskId(taskId);
        link.setOrd(order);
        return link;
    }

    private static AiTaskRunEntity run(LocalDateTime createdAt, String status) {
        AiTaskRunEntity run = new AiTaskRunEntity();
        run.setCreatedAt(createdAt);
        run.setStatus(status);
        return run;
    }
}
