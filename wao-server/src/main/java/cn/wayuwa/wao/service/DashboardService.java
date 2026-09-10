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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import cn.wayuwa.wao.domain.AiQuotaSnapshotEntity;
import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.domain.AiTaskRunTaskEntity;
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
    private static final int OFFICIAL_RESET_LOOKBACK_DAYS = 8;

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
     * 返回近 N 天真实额度采样的每日收盘值、下降幅度、额度上升事件与 Codex 官方重置。
     *
     * <p>同一账号/窗口一天内只取最后一次采样画趋势；“下降”只计算相邻有记录日期的真实差值，
     * 额度上升单独列为刷新事件，避免把重置误算成负消耗。Codex 官方重置只以七天窗口的供应商
     * {@code resetsAt} 跨周期推进为准；具体时间取上一周期的供应商时间，不拿采样发现时间冒充。
     */
    public AiQuotaHistoryDto quotaHistory(int days) {
        int span = Math.max(1, Math.min(days, 90));
        LocalDateTime from = LocalDate.now(ZONE).minusDays(span - 1L).atStartOfDay();
        List<AiQuotaSnapshotEntity> snapshots = quotaMapper.selectList(
                new LambdaQueryWrapper<AiQuotaSnapshotEntity>()
                        // 多读一个七天周期，只用于识别查询边界当天发生的官方重置。
                        .ge(AiQuotaSnapshotEntity::getObservedAt,
                                from.minusDays(OFFICIAL_RESET_LOOKBACK_DAYS))
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
        List<AiQuotaHistoryDto.OfficialResetEvent> officialResets = new ArrayList<>();
        for (Map.Entry<QuotaSeriesKey, List<AiQuotaSnapshotEntity>> entry : grouped.entrySet()) {
            List<AiQuotaSnapshotEntity> values = entry.getValue();
            AiQuotaSnapshotEntity firstInRange = values.stream()
                    .filter(value -> !value.getObservedAt().isBefore(from))
                    .findFirst().orElse(null);
            Map<LocalDate, AiQuotaSnapshotEntity> dailyLast = new LinkedHashMap<>();
            AiQuotaSnapshotEntity previous = null;
            for (AiQuotaSnapshotEntity current : values) {
                if (!current.getObservedAt().isBefore(from)) {
                    dailyLast.put(current.getObservedAt().toLocalDate(), current);
                }
                if (previous != null && !current.getObservedAt().isBefore(from)
                        && current.getRemainingPercent() > previous.getRemainingPercent()) {
                    refreshes.add(new AiQuotaHistoryDto.RefreshEvent(current.getAccountLabel(),
                            current.getProvider(), current.getWindowLabel(), previous.getRemainingPercent(),
                            current.getRemainingPercent(), current.getRemainingPercent() - previous.getRemainingPercent(),
                            text(current.getResetsAt()), text(current.getObservedAt())));
                }
                if (previous != null && officialCodexWeeklyReset(entry.getKey(), previous, current, from)) {
                    officialResets.add(new AiQuotaHistoryDto.OfficialResetEvent(current.getAccountLabel(),
                            current.getWindowLabel(), previous.getRemainingPercent(), current.getRemainingPercent(),
                            text(previous.getResetsAt()), text(current.getObservedAt())));
                }
                previous = current;
            }
            if (firstInRange == null) continue;
            List<AiQuotaHistoryDto.DailyPoint> points = new ArrayList<>();
            Integer previousRemaining = null;
            for (Map.Entry<LocalDate, AiQuotaSnapshotEntity> day : dailyLast.entrySet()) {
                int remaining = day.getValue().getRemainingPercent();
                int decrease = previousRemaining == null ? 0 : Math.max(0, previousRemaining - remaining);
                points.add(new AiQuotaHistoryDto.DailyPoint(day.getKey().format(DATE_FMT), remaining, decrease));
                previousRemaining = remaining;
            }
            series.add(new AiQuotaHistoryDto.Series(seriesId(entry.getKey()), firstInRange.getAccountLabel(),
                    firstInRange.getProvider(), firstInRange.getWindowLabel(), points));
        }
        refreshes.sort(Comparator.comparing(AiQuotaHistoryDto.RefreshEvent::observedAt).reversed());
        if (refreshes.size() > 30) refreshes = new ArrayList<>(refreshes.subList(0, 30));
        officialResets.sort(Comparator.comparing(AiQuotaHistoryDto.OfficialResetEvent::resetAt).reversed());
        if (officialResets.size() > 30) officialResets = new ArrayList<>(officialResets.subList(0, 30));
        int recordedSamples = (int) snapshots.stream()
                .filter(snapshot -> snapshot.getObservedAt() != null && !snapshot.getObservedAt().isBefore(from))
                .count();
        return new AiQuotaHistoryDto(recordedSamples, series, refreshes, officialResets);
    }

    private static boolean officialCodexWeeklyReset(QuotaSeriesKey key, AiQuotaSnapshotEntity previous,
            AiQuotaSnapshotEntity current, LocalDateTime from) {
        if (!"codex".equalsIgnoreCase(current.getProvider()) || !weeklyWindow(key.windowKey())) return false;
        LocalDateTime previousReset = previous.getResetsAt();
        LocalDateTime currentReset = current.getResetsAt();
        return previousReset != null && currentReset != null
                && currentReset.isAfter(previousReset)
                && !previousReset.isBefore(from)
                && !previousReset.isAfter(current.getObservedAt());
    }

    private static boolean weeklyWindow(String windowKey) {
        return "weekly".equals(windowKey) || "weekly_all".equals(windowKey)
                || "seven_day".equals(windowKey) || (windowKey != null && windowKey.endsWith(":10080"));
    }

    /**
     * 把近 N 天 AI Run 换算成有效交付、单位 Token、失败与重试消耗和真实 AI 执行时长。
     *
     * <p>模型配置只在同一活动类型、每组至少三次且 Token 与 AI 单调时长均有记录时比较；
     * 返回帕累托候选而不是跨任务类型挑一个伪“最优”。
     */
    public AiUsageInsightsDto usageInsights(int days) {
        int span = Math.max(1, Math.min(days, 90));
        LocalDateTime from = LocalDate.now(ZONE).minusDays(span - 1L).atStartOfDay();
        List<AiTaskRunEntity> runs = runMapper.selectList(new LambdaQueryWrapper<AiTaskRunEntity>()
                .ge(AiTaskRunEntity::getCreatedAt, from));
        List<AiTaskRunEntity> completed = runs.stream().filter(DashboardService::terminal).toList();
        List<Long> durations = completed.stream()
                .filter(run -> run.getStartedAt() != null && run.getFinishedAt() != null)
                .map(run -> Math.max(0L, java.time.Duration.between(run.getStartedAt(), run.getFinishedAt()).toSeconds()))
                .sorted().toList();
        List<Long> aiDurations = completed.stream()
                .map(AiTaskRunEntity::getAiExecutionDurationMs)
                .filter(Objects::nonNull).filter(value -> value >= 0)
                .map(value -> Math.round(value / 1000.0)).sorted().toList();
        Long median = median(durations);
        Long medianAi = median(aiDurations);

        Map<String, List<String>> taskIdsByRun = taskIdsByRun(completed);
        List<AiTaskRunEntity> delivered = completed.stream().filter(DashboardService::delivered).toList();
        Set<String> deliveredTaskIds = delivered.stream()
                .flatMap(run -> taskIdsByRun.getOrDefault(run.getId(), List.of()).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<TaskEntity> deliveredTasks = deliveredTaskIds.isEmpty() ? List.of()
                : Objects.requireNonNullElse(taskMapper.selectBatchIds(deliveredTaskIds), List.of());
        int verifiedTasks = (int) deliveredTasks.stream().filter(task -> "已完成".equals(task.getStatus())).count();

        int metered = (int) completed.stream().filter(DashboardService::usageRecorded).count();
        long totalTokens = completed.stream().mapToLong(run -> valueOrZero(run.getTotalTokens())).sum();
        long failedTokens = completed.stream().filter(run -> !delivered(run))
                .mapToLong(run -> valueOrZero(run.getTotalTokens())).sum();
        List<AiTaskRunEntity> retries = completed.stream()
                .filter(run -> run.getRetryOfRunId() != null && !run.getRetryOfRunId().isBlank()).toList();
        long retryTokens = retries.stream().mapToLong(run -> valueOrZero(run.getTotalTokens())).sum();
        Long tokensPerDeliveredTask = deliveredTaskIds.isEmpty() || metered < completed.size() ? null
                : Math.round((double) totalTokens / deliveredTaskIds.size());

        List<AiUsageInsightsDto.Advice> advice = List.of(
                outcomeAdvice(completed, deliveredTaskIds.size(), totalTokens, tokensPerDeliveredTask, medianAi),
                qualityAdvice(completed, delivered.size(), deliveredTaskIds.size(), verifiedTasks, failedTokens),
                routingAdvice(completed),
                retryAdvice(completed.size(), retries.size(), retryTokens),
                accountingAdvice(completed.size(), metered));
        return new AiUsageInsightsDto(span, runs.size(), completed.size(), metered, aiDurations.size(),
                delivered.size(), deliveredTaskIds.size(), verifiedTasks, totalTokens, failedTokens,
                tokensPerDeliveredTask, retries.size(), retryTokens, median, medianAi, advice);
    }

    private Map<String, List<String>> taskIdsByRun(List<AiTaskRunEntity> runs) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (runs.isEmpty()) return result;
        for (AiTaskRunEntity run : runs) {
            List<String> fallback = new ArrayList<>();
            if (run.getTaskId() != null && !run.getTaskId().isBlank()) fallback.add(run.getTaskId());
            result.put(run.getId(), fallback);
        }
        List<AiTaskRunTaskEntity> links = runMapper.selectTaskLinks(
                runs.stream().map(AiTaskRunEntity::getId).toList());
        if (links == null) links = List.of();
        Set<String> runsWithLinks = new HashSet<>();
        for (var link : links) {
            if (link.getRunId() == null || link.getTaskId() == null || link.getTaskId().isBlank()) continue;
            if (runsWithLinks.add(link.getRunId())) result.put(link.getRunId(), new ArrayList<>());
            List<String> taskIds = result.computeIfAbsent(link.getRunId(), unused -> new ArrayList<>());
            if (!taskIds.contains(link.getTaskId())) taskIds.add(link.getTaskId());
        }
        return result;
    }

    private static AiUsageInsightsDto.Advice outcomeAdvice(List<AiTaskRunEntity> completed, int deliveredTasks,
            long totalTokens, Long tokensPerDeliveredTask, Long medianAi) {
        if (deliveredTasks == 0) {
            return new AiUsageInsightsDto.Advice("OUTCOME", "单位交付消耗",
                    "先完成一次部署与线上验收，再建立单位交付基线",
                    completed.size() + " 次已结束运行，尚无有效交付任务", false);
        }
        if (tokensPerDeliveredTask == null) {
            return new AiUsageInsightsDto.Advice("OUTCOME", "单位交付消耗",
                    "先补齐已结束运行的 Token 回传，再计算单位交付基线",
                    deliveredTasks + " 个任务已部署并线上验收，但记账覆盖不完整", false);
        }
        return new AiUsageInsightsDto.Advice("OUTCOME", "单位交付消耗",
                "以每个有效交付约 " + tokensPerDeliveredTask + " Token 作为当前基线",
                deliveredTasks + " 个任务已部署并线上验收；窗口共 " + totalTokens + " Token"
                        + (medianAi == null ? "；AI 实际时长仍未完整回传" : "；AI 实际执行中位 " + duration(medianAi)),
                medianAi != null);
    }

    private static AiUsageInsightsDto.Advice qualityAdvice(List<AiTaskRunEntity> completed, int deliveredRuns,
            int deliveredTasks, int verifiedTasks, long failedTokens) {
        if (completed.size() < 3) {
            return new AiUsageInsightsDto.Advice("QUALITY", "交付质量",
                    "继续积累至少 3 次已结束运行后再判断质量趋势",
                    "当前 " + completed.size() + " 次已结束运行，其中 " + deliveredRuns + " 次完成交付", false);
        }
        double successRate = (double) deliveredRuns / completed.size();
        String recommendation = deliveredRuns == completed.size()
                ? "保持当前交付门禁，并继续由成员验收已交付任务"
                : "先减少未通过部署或线上验收的运行，再比较 Token 和模型";
        return new AiUsageInsightsDto.Advice("QUALITY", "交付质量", recommendation,
                completed.size() + " 次已结束运行，交付成功率 " + percent(successRate) + "；"
                        + deliveredTasks + " 个交付任务中 " + verifiedTasks + " 个已由成员验收；失败消耗 "
                        + failedTokens + " Token（仅统计已回传用量）", true);
    }

    private static AiUsageInsightsDto.Advice routingAdvice(List<AiTaskRunEntity> completed) {
        Map<String, Map<String, RunStats>> byActivity = new LinkedHashMap<>();
        for (AiTaskRunEntity run : completed) {
            String model = effectiveModel(run);
            String effort = run.getReasoningEffort();
            if (model == null || effort == null || effort.isBlank()) continue;
            byActivity.computeIfAbsent(activityLabel(run), unused -> new LinkedHashMap<>())
                    .computeIfAbsent(model + " · " + effort, unused -> new RunStats()).add(run);
        }
        Map.Entry<String, Map<String, RunStats>> comparable = byActivity.entrySet().stream()
                .filter(entry -> entry.getValue().values().stream().filter(RunStats::comparable).count() >= 2)
                .max(Comparator.comparingInt(entry -> entry.getValue().values().stream()
                        .mapToInt(stats -> stats.count).sum())).orElse(null);
        if (comparable == null) {
            int comparableGroups = byActivity.values().stream().mapToInt(groups ->
                    (int) groups.values().stream().filter(RunStats::comparable).count()).sum();
            return new AiUsageInsightsDto.Advice("ROUTING", "模型与思考深度",
                    "同一任务类型至少让两个配置各完成 3 次，再比较质量、Token 与 AI 实际时长",
                    "当前可独立评测的配置组 " + comparableGroups + " 个；端到端耗时不参与模型快慢判断", false);
        }
        List<Map.Entry<String, RunStats>> candidates = comparable.getValue().entrySet().stream()
                .filter(entry -> entry.getValue().comparable()).toList();
        List<Map.Entry<String, RunStats>> frontier = candidates.stream()
                .filter(candidate -> candidates.stream().noneMatch(other -> other != candidate
                        && dominates(other.getValue(), candidate.getValue()))).toList();
        String names = frontier.stream().map(Map.Entry::getKey).collect(Collectors.joining("、"));
        String evidence = frontier.stream().map(entry -> entry.getKey() + "：" + entry.getValue().count
                + " 次，成功率 " + percent(entry.getValue().successRate()) + "，平均 "
                + entry.getValue().averageTokens() + " Token，AI " + duration(entry.getValue().averageAiSeconds()))
                .collect(Collectors.joining("；"));
        return new AiUsageInsightsDto.Advice("ROUTING", "模型与思考深度",
                comparable.getKey() + "的帕累托候选为 " + names,
                evidence + "；候选只表示当前样本中没有被质量、Token、时延同时支配", true);
    }

    private static boolean dominates(RunStats left, RunStats right) {
        boolean noWorse = left.successRate() >= right.successRate()
                && left.averageTokens() <= right.averageTokens()
                && left.averageAiSeconds() <= right.averageAiSeconds();
        boolean strictlyBetter = left.successRate() > right.successRate()
                || left.averageTokens() < right.averageTokens()
                || left.averageAiSeconds() < right.averageAiSeconds();
        return noWorse && strictlyBetter;
    }

    private static AiUsageInsightsDto.Advice retryAdvice(int completed, int retries, long retryTokens) {
        if (completed == 0) {
            return new AiUsageInsightsDto.Advice("RETRY", "重试消耗", "等待真实运行后再检查重试开销",
                    "当前没有已结束运行", false);
        }
        if (retries == 0) {
            return new AiUsageInsightsDto.Advice("RETRY", "重试消耗", "当前没有可见的跨运行重试消耗",
                    completed + " 次已结束运行均不是失败重跑；Runner 内部自动重试尚未单独计数", true);
        }
        return new AiUsageInsightsDto.Advice("RETRY", "重试消耗",
                "优先复盘重复失败原因，把稳定轮询与恢复步骤下沉到确定性流程",
                retries + " 次失败重跑共消耗 " + retryTokens + " Token", true);
    }

    private static AiUsageInsightsDto.Advice accountingAdvice(int completed, int metered) {
        if (completed == 0) {
            return new AiUsageInsightsDto.Advice("ACCOUNTING", "记账覆盖", "等待真实运行后再检查用量回传",
                    "当前没有已结束运行", false);
        }
        double coverage = (double) metered / completed;
        return new AiUsageInsightsDto.Advice("ACCOUNTING", "记账覆盖",
                coverage >= 0.9 ? "当前用量覆盖可用于观察趋势" : "先修复未回传 Token 的 Runner 或 CLI 事件解析",
                metered + "/" + completed + " 次已结束运行回传 Token（" + percent(coverage) + "）",
                coverage >= 0.9);
    }

    private static boolean terminal(AiTaskRunEntity run) {
        return run != null && List.of("succeeded", "failed", "cancelled").contains(run.getStatus());
    }

    private static boolean delivered(AiTaskRunEntity run) {
        if (!"succeeded".equals(run.getStatus())) return false;
        String stage = run.getDeliveryStage();
        return stage == null || stage.isBlank() || "deployment_succeeded".equals(stage);
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

    private static Long median(List<Long> values) {
        return values.isEmpty() ? null : values.get(values.size() / 2);
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

    private static final class RunStats {
        private int count;
        private int delivered;
        private int metered;
        private long tokens;
        private int timed;
        private long aiSeconds;

        void add(AiTaskRunEntity run) {
            count++;
            if (DashboardService.delivered(run)) delivered++;
            if (usageRecorded(run)) {
                metered++;
                tokens += valueOrZero(run.getTotalTokens());
            }
            if (run.getAiExecutionDurationMs() != null && run.getAiExecutionDurationMs() >= 0) {
                timed++;
                aiSeconds += Math.round(run.getAiExecutionDurationMs() / 1000.0);
            }
        }

        boolean comparable() { return count >= 3 && metered == count && timed == count; }
        double successRate() { return count == 0 ? 0 : (double) delivered / count; }
        long averageTokens() { return metered == 0 ? Long.MAX_VALUE : Math.round((double) tokens / metered); }
        long averageAiSeconds() { return timed == 0 ? Long.MAX_VALUE : Math.round((double) aiSeconds / timed); }
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
