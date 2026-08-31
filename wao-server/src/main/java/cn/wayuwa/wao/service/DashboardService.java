package cn.wayuwa.wao.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import cn.wayuwa.wao.domain.AiQuotaSnapshotEntity;
import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.AiQuotaHistoryDto;
import cn.wayuwa.wao.dto.AiRunTrendItem;
import cn.wayuwa.wao.dto.AiUsageInsightsDto;
import cn.wayuwa.wao.dto.DashboardSummaryDto;
import cn.wayuwa.wao.dto.EngineUsageItem;
import cn.wayuwa.wao.dto.TaskTrendItem;
import cn.wayuwa.wao.mapper.AiRunnerNodeMapper;
import cn.wayuwa.wao.mapper.AiQuotaSnapshotMapper;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

/** 仪表盘统计：AI 运行频次与用量、任务完成、引擎占比。 */
@Service
public class DashboardService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final AiTaskRunMapper runMapper;
    private final TaskMapper taskMapper;
    private final AiRunnerNodeMapper runnerMapper;
    private final AiQuotaSnapshotMapper quotaMapper;

    public DashboardService(AiTaskRunMapper runMapper, TaskMapper taskMapper, AiRunnerNodeMapper runnerMapper,
            AiQuotaSnapshotMapper quotaMapper) {
        this.runMapper = runMapper;
        this.taskMapper = taskMapper;
        this.runnerMapper = runnerMapper;
        this.quotaMapper = quotaMapper;
    }

    /** 顶部指标卡片：总运行数、当前活动运行、在线 Runner 数、本周任务完成情况。 */
    public DashboardSummaryDto summary() {
        List<AiTaskRunEntity> runs = runMapper.selectList(null);
        int total = runs.size();
        int active = (int) runs.stream()
                .filter(r -> "queued".equals(r.getStatus()) || "claimed".equals(r.getStatus())
                        || "running".equals(r.getStatus()))
                .count();

        LocalDateTime cutoff = LocalDateTime.now(ZONE).minusMinutes(2);
        int onlineRunners = (int) runnerMapper.selectList(null).stream()
                .filter(r -> r.getLastSeenAt() != null && r.getLastSeenAt().isAfter(cutoff))
                .count();

        LocalDate today = LocalDate.now(ZONE);
        LocalDateTime weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
        List<TaskEntity> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<TaskEntity>().ge(TaskEntity::getCompletedAt, weekStart));
        int completed = (int) tasks.stream().filter(t -> "已完成".equals(t.getStatus())).count();

        List<AiTaskRunEntity> weekRuns = runMapper.selectList(
                new LambdaQueryWrapper<AiTaskRunEntity>().ge(AiTaskRunEntity::getCreatedAt, weekStart));
        int succeededWeek = (int) weekRuns.stream().filter(r -> "succeeded".equals(r.getStatus())).count();
        int failedWeek = (int) weekRuns.stream().filter(r -> "failed".equals(r.getStatus())).count();

        return new DashboardSummaryDto(total, active, onlineRunners, completed, succeededWeek, failedWeek);
    }

    /**
     * 近 N 天 AI 运行频次与 Token 用量（按运行创建日期分桶）。
     *
     * <p>Token 只累加 Runner 从 CLI 结束事件真实回传的字段；{@code recordedRuns} 单独保留记账覆盖数，
     * 避免把没有回传用量的运行解释成零消耗。
     */
    public List<AiRunTrendItem> aiRunTrend(int days) {
        int span = Math.max(1, Math.min(days, 365));
        LocalDate today = LocalDate.now(ZONE);
        LocalDate start = today.minusDays(span - 1);
        LocalDateTime from = start.atStartOfDay();

        List<AiTaskRunEntity> runs = runMapper.selectList(
                new LambdaQueryWrapper<AiTaskRunEntity>()
                        .ge(AiTaskRunEntity::getCreatedAt, from));

        // 按日期分桶，保留空桶顺序。
        Map<LocalDate, List<AiTaskRunEntity>> buckets = new LinkedHashMap<>();
        for (long i = 0; i < span; i++) {
            buckets.put(start.plusDays(i), new ArrayList<>());
        }
        for (AiTaskRunEntity run : runs) {
            LocalDate d = run.getCreatedAt().toLocalDate();
            buckets.computeIfAbsent(d, k -> new ArrayList<>()).add(run);
        }

        List<AiRunTrendItem> result = new ArrayList<>(buckets.size());
        for (Map.Entry<LocalDate, List<AiTaskRunEntity>> entry : buckets.entrySet()) {
            List<AiTaskRunEntity> list = entry.getValue();
            int total = list.size();
            int succeeded = (int) list.stream().filter(r -> "succeeded".equals(r.getStatus())).count();
            int failed = (int) list.stream().filter(r -> "failed".equals(r.getStatus())).count();
            int cancelled = (int) list.stream().filter(r -> "cancelled".equals(r.getStatus())).count();
            int recordedRuns = (int) list.stream().filter(DashboardService::usageRecorded).count();
            long inputTokens = list.stream().mapToLong(r -> valueOrZero(r.getInputTokens())).sum();
            long outputTokens = list.stream().mapToLong(r -> valueOrZero(r.getOutputTokens())).sum();
            long cachedInputTokens = list.stream().mapToLong(r -> valueOrZero(r.getCachedInputTokens())).sum();
            long totalTokens = list.stream().mapToLong(r -> valueOrZero(r.getTotalTokens())).sum();
            result.add(new AiRunTrendItem(entry.getKey().format(DATE_FMT), total, succeeded, failed, cancelled,
                    recordedRuns, inputTokens, outputTokens, cachedInputTokens, totalTokens));
        }
        return result;
    }

    /** 近 N 天任务完成 / 创建趋势（按天分桶）。 */
    public List<TaskTrendItem> taskTrend(int days) {
        int span = Math.max(1, Math.min(days, 365));
        LocalDate today = LocalDate.now(ZONE);
        LocalDate start = today.minusDays(span - 1);
        LocalDateTime from = start.atStartOfDay();

        List<TaskEntity> completed = taskMapper.selectList(
                new LambdaQueryWrapper<TaskEntity>()
                        .ge(TaskEntity::getCompletedAt, from)
                        .eq(TaskEntity::getStatus, "已完成"));
        // 任务创建时间没有专门字段，复用 detailJson 中 activity 的创建时间过于复杂，
        // 这里只统计完成趋势；创建统计需要新增字段，本阶段跳过。
        List<TaskEntity> all = taskMapper.selectList(null);

        Map<LocalDate, Integer> completedByDay = new LinkedHashMap<>();
        Map<LocalDate, Integer> createdByDay = new LinkedHashMap<>();
        for (long i = 0; i < span; i++) {
            completedByDay.put(start.plusDays(i), 0);
            createdByDay.put(start.plusDays(i), 0);
        }
        for (TaskEntity t : completed) {
            if (t.getCompletedAt() == null) continue;
            LocalDate d = t.getCompletedAt().toLocalDate();
            completedByDay.merge(d, 1, Integer::sum);
        }
        // 按 id 里的时间戳兜底估算创建时间（t + 毫秒数）；仅做粗略统计。
        for (TaskEntity t : all) {
            if (t.getId() == null || t.getId().length() < 2) continue;
            try {
                long ts = Long.parseLong(t.getId().substring(1));
                LocalDateTime created = LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(ts), ZONE);
                if (created.isBefore(from)) continue;
                createdByDay.merge(created.toLocalDate(), 1, Integer::sum);
            } catch (NumberFormatException ignore) {
                // 历史演示 id 不是纯数字，直接跳过。
            }
        }

        List<TaskTrendItem> result = new ArrayList<>(span);
        for (long i = 0; i < span; i++) {
            LocalDate d = start.plusDays(i);
            result.add(new TaskTrendItem(d.format(DATE_FMT),
                    completedByDay.getOrDefault(d, 0),
                    createdByDay.getOrDefault(d, 0)));
        }
        return result;
    }

    /** 各引擎历史使用次数。 */
    public List<EngineUsageItem> engineUsage() {
        List<AiTaskRunEntity> runs = runMapper.selectList(
                new LambdaQueryWrapper<AiTaskRunEntity>().isNotNull(AiTaskRunEntity::getEngine));
        Map<String, Long> counts = runs.stream()
                .filter(r -> r.getEngine() != null && !r.getEngine().isBlank())
                .collect(Collectors.groupingBy(AiTaskRunEntity::getEngine, Collectors.counting()));
        return AiRunService.ENGINES.stream()
                .map(e -> new EngineUsageItem(e, label(e), counts.getOrDefault(e, 0L).intValue()))
                .toList();
    }

    /**
     * 返回近 N 天真实额度采样的每日收盘值、下降幅度与剩余额度上升事件。
     *
     * <p>同一账号/窗口一天内只取最后一次采样画趋势；“下降”只计算相邻有记录日期的真实差值，
     * 额度上升单独列为刷新事件，避免把重置误算成负消耗。
     */
    public AiQuotaHistoryDto quotaHistory(int days) {
        int span = Math.max(1, Math.min(days, 90));
        LocalDateTime from = LocalDate.now(ZONE).minusDays(span - 1L).atStartOfDay();
        List<AiQuotaSnapshotEntity> snapshots = quotaMapper.selectList(
                new LambdaQueryWrapper<AiQuotaSnapshotEntity>()
                        .ge(AiQuotaSnapshotEntity::getObservedAt, from)
                        .orderByAsc(AiQuotaSnapshotEntity::getObservedAt)
                        .orderByAsc(AiQuotaSnapshotEntity::getId));

        Map<QuotaSeriesKey, List<AiQuotaSnapshotEntity>> grouped = new LinkedHashMap<>();
        for (AiQuotaSnapshotEntity snapshot : snapshots) {
            if (snapshot.getObservedAt() == null || snapshot.getRemainingPercent() == null) continue;
            QuotaSeriesKey key = new QuotaSeriesKey(snapshot.getSourceKey(), snapshot.getWindowKey());
            grouped.computeIfAbsent(key, unused -> new ArrayList<>()).add(snapshot);
        }

        List<AiQuotaHistoryDto.Series> series = new ArrayList<>();
        List<AiQuotaHistoryDto.RefreshEvent> refreshes = new ArrayList<>();
        for (Map.Entry<QuotaSeriesKey, List<AiQuotaSnapshotEntity>> entry : grouped.entrySet()) {
            List<AiQuotaSnapshotEntity> values = entry.getValue();
            AiQuotaSnapshotEntity first = values.get(0);
            Map<LocalDate, AiQuotaSnapshotEntity> dailyLast = new LinkedHashMap<>();
            AiQuotaSnapshotEntity previous = null;
            for (AiQuotaSnapshotEntity current : values) {
                dailyLast.put(current.getObservedAt().toLocalDate(), current);
                if (previous != null && current.getRemainingPercent() > previous.getRemainingPercent()) {
                    refreshes.add(new AiQuotaHistoryDto.RefreshEvent(current.getAccountLabel(),
                            current.getProvider(), current.getWindowLabel(), previous.getRemainingPercent(),
                            current.getRemainingPercent(), current.getRemainingPercent() - previous.getRemainingPercent(),
                            text(current.getResetsAt()), text(current.getObservedAt())));
                }
                previous = current;
            }
            List<AiQuotaHistoryDto.DailyPoint> points = new ArrayList<>();
            Integer previousRemaining = null;
            for (Map.Entry<LocalDate, AiQuotaSnapshotEntity> day : dailyLast.entrySet()) {
                int remaining = day.getValue().getRemainingPercent();
                int decrease = previousRemaining == null ? 0 : Math.max(0, previousRemaining - remaining);
                points.add(new AiQuotaHistoryDto.DailyPoint(day.getKey().format(DATE_FMT), remaining, decrease));
                previousRemaining = remaining;
            }
            series.add(new AiQuotaHistoryDto.Series(seriesId(entry.getKey()), first.getAccountLabel(),
                    first.getProvider(), first.getWindowLabel(), points));
        }
        refreshes.sort(Comparator.comparing(AiQuotaHistoryDto.RefreshEvent::observedAt).reversed());
        if (refreshes.size() > 30) refreshes = new ArrayList<>(refreshes.subList(0, 30));
        return new AiQuotaHistoryDto(snapshots.size(), series, refreshes);
    }

    /**
     * 根据近 N 天已经落库的模型、思考深度、耗时、成功状态和活动类型生成可解释建议。
     *
     * <p>每项建议都返回样本证据；不足三次运行或不足三个活跃日时明确标记数据不足，
     * 不把偶然一次成功包装成“当前最优”。
     */
    public AiUsageInsightsDto usageInsights(int days) {
        int span = Math.max(1, Math.min(days, 90));
        LocalDateTime from = LocalDate.now(ZONE).minusDays(span - 1L).atStartOfDay();
        List<AiTaskRunEntity> runs = runMapper.selectList(new LambdaQueryWrapper<AiTaskRunEntity>()
                .ge(AiTaskRunEntity::getCreatedAt, from));
        List<AiTaskRunEntity> completed = runs.stream().filter(DashboardService::terminal).toList();
        List<Long> durations = completed.stream().filter(run -> run.getStartedAt() != null && run.getFinishedAt() != null)
                .map(run -> Math.max(0L, java.time.Duration.between(run.getStartedAt(), run.getFinishedAt()).toSeconds()))
                .sorted().toList();
        Long median = durations.isEmpty() ? null : durations.get(durations.size() / 2);

        List<AiUsageInsightsDto.Advice> advice = List.of(
                rankedAdvice("MODEL", "模型选择", completed, DashboardService::effectiveModel,
                        "优先在同类任务中使用 %s", "还没有至少 3 次带模型信息的已结束运行"),
                rankedAdvice("REASONING", "思考深度", completed, AiTaskRunEntity::getReasoningEffort,
                        "优先以 %s 思考深度作为同类任务起点", "还没有至少 3 次带思考深度的已结束运行"),
                speedAdvice(completed, median),
                frequencyAdvice(completed),
                rankedAdvice("DIRECTION", "后续任务方向", completed, DashboardService::activityLabel,
                        "近期可优先安排%s类任务", "还没有至少 3 次可分类的已结束运行"));
        return new AiUsageInsightsDto(span, runs.size(), completed.size(),
                (int) runs.stream().filter(DashboardService::usageRecorded).count(), median, advice);
    }

    private static AiUsageInsightsDto.Advice rankedAdvice(String category, String title,
            List<AiTaskRunEntity> runs, java.util.function.Function<AiTaskRunEntity, String> classifier,
            String recommendation, String insufficient) {
        Map<String, RunStats> stats = new LinkedHashMap<>();
        for (AiTaskRunEntity run : runs) {
            String key = classifier.apply(run);
            if (key == null || key.isBlank()) continue;
            stats.computeIfAbsent(key, unused -> new RunStats()).add(run);
        }
        RunStatsChoice best = stats.entrySet().stream()
                .map(entry -> new RunStatsChoice(entry.getKey(), entry.getValue()))
                .max(Comparator.comparingDouble((RunStatsChoice value) -> value.stats.successRate())
                        .thenComparingInt(value -> value.stats.count)
                        .thenComparingLong(value -> -value.stats.averageTokens()))
                .orElse(null);
        int samples = stats.values().stream().mapToInt(value -> value.count).sum();
        if (best == null || samples < 3 || best.stats.count < 2) {
            return new AiUsageInsightsDto.Advice(category, title, "继续积累真实运行记录后再给建议",
                    insufficient + "（当前 " + samples + " 次）", false);
        }
        return new AiUsageInsightsDto.Advice(category, title,
                recommendation.formatted(best.key),
                best.key + "：" + best.stats.count + " 次，成功率 " + percent(best.stats.successRate())
                        + (best.stats.metered == 0 ? "" : "，平均 " + best.stats.averageTokens() + " Token"), true);
    }

    private static AiUsageInsightsDto.Advice speedAdvice(List<AiTaskRunEntity> completed, Long median) {
        List<AiTaskRunEntity> timed = completed.stream()
                .filter(run -> "succeeded".equals(run.getStatus()))
                .filter(run -> run.getStartedAt() != null && run.getFinishedAt() != null).toList();
        if (timed.size() < 3 || median == null) {
            return new AiUsageInsightsDto.Advice("SPEED", "完成速度", "继续积累带起止时间的成功运行后再比较速度",
                    "当前只有 " + timed.size() + " 次可用耗时样本", false);
        }
        Map<String, List<Long>> byConfig = new LinkedHashMap<>();
        for (AiTaskRunEntity run : timed) {
            String model = Objects.requireNonNullElse(effectiveModel(run), "未记录模型");
            String effort = Objects.requireNonNullElse(run.getReasoningEffort(), "未记录深度");
            byConfig.computeIfAbsent(model + " · " + effort, unused -> new ArrayList<>())
                    .add(java.time.Duration.between(run.getStartedAt(), run.getFinishedAt()).toSeconds());
        }
        Map.Entry<String, List<Long>> fastest = byConfig.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= 2)
                .min(Comparator.comparingLong(entry -> average(entry.getValue()))).orElse(null);
        if (fastest == null) {
            return new AiUsageInsightsDto.Advice("SPEED", "完成速度", "先保持当前配置并继续积累同配置样本",
                    "共 " + timed.size() + " 次成功耗时样本，但没有同配置至少 2 次的可比组；总体中位 "
                            + duration(median), false);
        }
        return new AiUsageInsightsDto.Advice("SPEED", "完成速度", "同类任务需要速度时优先尝试 " + fastest.getKey(),
                fastest.getValue().size() + " 次成功运行平均 " + duration(average(fastest.getValue()))
                        + "；总体中位 " + duration(median), true);
    }

    private static AiUsageInsightsDto.Advice frequencyAdvice(List<AiTaskRunEntity> completed) {
        Map<LocalDate, List<AiTaskRunEntity>> activeDays = completed.stream()
                .filter(run -> run.getCreatedAt() != null)
                .collect(Collectors.groupingBy(run -> run.getCreatedAt().toLocalDate()));
        if (activeDays.size() < 3) {
            return new AiUsageInsightsDto.Advice("FREQUENCY", "使用频率", "继续积累至少 3 个活跃日后再判断运行节奏",
                    "当前只有 " + activeDays.size() + " 个活跃日", false);
        }
        double average = activeDays.values().stream().mapToInt(List::size).average().orElse(0);
        long succeeded = completed.stream().filter(run -> "succeeded".equals(run.getStatus())).count();
        return new AiUsageInsightsDto.Advice("FREQUENCY", "使用频率",
                "以当前每个活跃日约 %.1f 次作为观察基线，并结合额度下降趋势调整".formatted(average),
                activeDays.size() + " 个活跃日共 " + completed.size() + " 次已结束运行，成功率 "
                        + percent(completed.isEmpty() ? 0 : (double) succeeded / completed.size()), true);
    }

    private static boolean terminal(AiTaskRunEntity run) {
        return run != null && List.of("succeeded", "failed", "cancelled").contains(run.getStatus());
    }

    private static String effectiveModel(AiTaskRunEntity run) {
        return textValue(run.getModel(), run.getRequestedModel());
    }

    private static String activityLabel(AiTaskRunEntity run) {
        return switch (Objects.requireNonNullElse(run.getActivityType(), "FEATURE_DEVELOPMENT")) {
            case "AI_INSPECTION" -> "AI 巡检";
            case "FEATURE_DEVELOPMENT" -> "功能开发";
            default -> run.getActivityType();
        };
    }

    private static String textValue(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) return primary;
        return fallback != null && !fallback.isBlank() ? fallback : null;
    }

    private static long average(List<Long> values) {
        return Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    private static String duration(long seconds) {
        if (seconds < 60) return seconds + " 秒";
        return String.format(java.util.Locale.ROOT, "%.1f 分钟", seconds / 60.0);
    }

    private static String percent(double value) {
        return Math.round(value * 100) + "%";
    }

    private static String seriesId(QuotaSeriesKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((key.sourceKey + "\n" + key.windowKey).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("运行环境缺少 SHA-256", impossible);
        }
    }

    private static String text(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private record QuotaSeriesKey(String sourceKey, String windowKey) { }
    private record RunStatsChoice(String key, RunStats stats) { }

    private static final class RunStats {
        private int count;
        private int succeeded;
        private int metered;
        private long tokens;

        void add(AiTaskRunEntity run) {
            count++;
            if ("succeeded".equals(run.getStatus())) succeeded++;
            if (usageRecorded(run)) {
                metered++;
                tokens += valueOrZero(run.getTotalTokens());
            }
        }

        double successRate() { return count == 0 ? 0 : (double) succeeded / count; }
        long averageTokens() { return metered == 0 ? Long.MAX_VALUE : Math.round((double) tokens / metered); }
    }

    private static String label(String engine) {
        return switch (engine) {
            case "codex" -> "Codex";
            case "claude" -> "Claude Code";
            case "qoder" -> "Qoder";
            default -> engine;
        };
    }

    private static boolean usageRecorded(AiTaskRunEntity run) {
        return valueOrZero(run.getTotalTokens()) > 0;
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0 : value;
    }
}
