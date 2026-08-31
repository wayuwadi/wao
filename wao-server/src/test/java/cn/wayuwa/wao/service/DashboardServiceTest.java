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
import cn.wayuwa.wao.domain.AiQuotaSnapshotEntity;
import cn.wayuwa.wao.dto.AiQuotaHistoryDto;
import cn.wayuwa.wao.dto.AiRunTrendItem;
import cn.wayuwa.wao.dto.AiUsageInsightsDto;
import cn.wayuwa.wao.mapper.AiQuotaSnapshotMapper;
import cn.wayuwa.wao.mapper.AiRunnerNodeMapper;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import org.junit.jupiter.api.Test;

class DashboardServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final AiTaskRunMapper runMapper = mock(AiTaskRunMapper.class);
    private final AiQuotaSnapshotMapper quotaMapper = mock(AiQuotaSnapshotMapper.class);
    private final DashboardService service = new DashboardService(
            runMapper, mock(TaskMapper.class), mock(AiRunnerNodeMapper.class), quotaMapper);

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
    }

    @Test
    void usageInsightsRanksOnlyGroundedRunFactsAndMarksSufficientSamples() {
        LocalDate today = LocalDate.now(ZONE);
        AiTaskRunEntity first = analyzedRun(today.minusDays(2).atTime(9, 0), "gpt-5.6", "high", 600, true);
        AiTaskRunEntity second = analyzedRun(today.minusDays(1).atTime(9, 0), "gpt-5.6", "high", 720, true);
        AiTaskRunEntity third = analyzedRun(today.atTime(9, 0), "gpt-5.6", "high", 660, true);
        when(runMapper.selectList(any())).thenReturn(List.of(first, second, third));

        AiUsageInsightsDto insights = service.usageInsights(30);

        assertThat(insights.completedRuns()).isEqualTo(3);
        assertThat(insights.medianDurationSeconds()).isEqualTo(660);
        assertThat(insights.advice()).extracting(AiUsageInsightsDto.Advice::category)
                .containsExactly("MODEL", "REASONING", "SPEED", "FREQUENCY", "DIRECTION");
        assertThat(insights.advice()).allMatch(AiUsageInsightsDto.Advice::sufficientData);
        assertThat(insights.advice().get(0).recommendation()).contains("gpt-5.6");
        assertThat(insights.advice().get(1).recommendation()).contains("high");
    }

    private static AiQuotaSnapshotEntity quota(LocalDateTime at, int remaining) {
        AiQuotaSnapshotEntity value = new AiQuotaSnapshotEntity();
        value.setSourceKey("central:aa1");
        value.setAccountLabel("Codex 主账号");
        value.setProvider("codex");
        value.setWindowKey("weekly");
        value.setWindowLabel("七天额度");
        value.setRemainingPercent(remaining);
        value.setObservedAt(at);
        return value;
    }

    private static AiTaskRunEntity analyzedRun(LocalDateTime createdAt, String model, String effort,
            long durationSeconds, boolean succeeded) {
        AiTaskRunEntity run = run(createdAt, succeeded ? "succeeded" : "failed");
        run.setModel(model);
        run.setReasoningEffort(effort);
        run.setActivityType("FEATURE_DEVELOPMENT");
        run.setTotalTokens(10_000L);
        run.setStartedAt(createdAt);
        run.setFinishedAt(createdAt.plusSeconds(durationSeconds));
        return run;
    }

    private static AiTaskRunEntity run(LocalDateTime createdAt, String status) {
        AiTaskRunEntity run = new AiTaskRunEntity();
        run.setCreatedAt(createdAt);
        run.setStatus(status);
        return run;
    }
}
