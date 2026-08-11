package cn.wayuwa.wao.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.AiRunTrendItem;
import cn.wayuwa.wao.dto.DashboardSummaryDto;
import cn.wayuwa.wao.dto.EngineUsageItem;
import cn.wayuwa.wao.dto.TaskTrendItem;
import cn.wayuwa.wao.mapper.AiRunnerNodeMapper;
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

    public DashboardService(AiTaskRunMapper runMapper, TaskMapper taskMapper, AiRunnerNodeMapper runnerMapper) {
        this.runMapper = runMapper;
        this.taskMapper = taskMapper;
        this.runnerMapper = runnerMapper;
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
