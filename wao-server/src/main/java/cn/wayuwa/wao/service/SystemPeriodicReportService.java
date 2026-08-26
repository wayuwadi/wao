package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.domain.SystemPeriodicReportEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.SystemPeriodicReportDto;
import cn.wayuwa.wao.dto.SystemReportRunDto;
import cn.wayuwa.wao.dto.SystemReportSettingsDto;
import cn.wayuwa.wao.dto.SystemReportTaskDto;
import cn.wayuwa.wao.dto.SaveSystemReportSettingsRequest;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import cn.wayuwa.wao.mapper.SystemPeriodicReportMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 从任务与 AI Run 的真实持久化数据生成系统日、周、月快照。 */
@Service
public class SystemPeriodicReportService {
    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final TypeReference<List<SystemReportTaskDto>> TASK_LIST = new TypeReference<>() { };
    private static final TypeReference<List<SystemReportRunDto>> RUN_LIST = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<SystemReportSettingsDto> SETTINGS_TYPE = new TypeReference<>() { };
    private static final List<String> ALL_PERIODS = List.of("DAY", "WEEK", "MONTH");
    private static final List<String> ALL_AGGREGATIONS = List.of("TASK_CREATED", "TASK_COMPLETED", "AI_RUN");
    private static final Set<Integer> REFRESH_FREQUENCIES = Set.of(1, 3, 6, 12, 24);
    private static final SystemReportSettingsDto DEFAULT_SETTINGS = new SystemReportSettingsDto(
            true, "00:00", 24, ALL_PERIODS, ALL_AGGREGATIONS);

    private final SystemPeriodicReportMapper reports;
    private final ProjectMapper projects;
    private final TaskMapper tasks;
    private final AiTaskRunMapper runs;
    private final SettingService settings;
    private final ObjectMapper json;

    public SystemPeriodicReportService(SystemPeriodicReportMapper reports, ProjectMapper projects,
            TaskMapper tasks, AiTaskRunMapper runs, SettingService settings, ObjectMapper json) {
        this.reports = reports;
        this.projects = projects;
        this.tasks = tasks;
        this.runs = runs;
        this.settings = settings;
        this.json = json;
    }

    /** 读取指定完整周期的快照；未生成时返回范围和明确的空状态。 */
    public SystemPeriodicReportDto overview(String period, LocalDate anchorDate) {
        PeriodRange range = resolvePeriod(period, anchorDate, LocalDate.now(SHANGHAI));
        SystemPeriodicReportEntity entity = reports.selectOne(new LambdaQueryWrapper<SystemPeriodicReportEntity>()
                .eq(SystemPeriodicReportEntity::getPeriodType, range.type())
                .eq(SystemPeriodicReportEntity::getPeriodKey, range.key()));
        return entity == null ? empty(range, settings()) : toDto(entity);
    }

    /** 使用当前数据库中的真实数据生成或更新指定完整周期。 */
    @Transactional
    public SystemPeriodicReportDto generate(String period, LocalDate anchorDate) {
        PeriodRange range = resolvePeriod(period, anchorDate, LocalDate.now(SHANGHAI));
        return generate(range, settings());
    }

    /** 读取持久化报告设置；缺失或损坏时安全回退为午夜每日刷新全部报告与汇总项。 */
    public SystemReportSettingsDto settings() {
        String raw = settings.get(SettingService.SYSTEM_REPORT_SETTINGS);
        if (raw == null || raw.isBlank()) return DEFAULT_SETTINGS;
        try {
            SystemReportSettingsDto stored = json.readValue(raw, SETTINGS_TYPE);
            return canonicalSettings(stored.automaticEnabled(), stored.refreshTime(),
                    stored.refreshFrequencyHours(), stored.periods(), stored.aggregationOptions());
        } catch (RuntimeException | JsonProcessingException exception) {
            return DEFAULT_SETTINGS;
        }
    }

    /** 校验并整体保存团队共享报告设置，避免调度读取到新旧混合参数。 */
    public SystemReportSettingsDto saveSettings(SaveSystemReportSettingsRequest request) {
        if (request == null) throw new BadRequestException("报告设置不能为空");
        SystemReportSettingsDto value = canonicalSettings(request.automaticEnabled(), request.refreshTime(),
                request.refreshFrequencyHours(), request.periods(), request.aggregationOptions());
        settings.put(SettingService.SYSTEM_REPORT_SETTINGS, write(value));
        return value;
    }

    /** 按共享设置补齐最近已经结束的周期，供主节点启动时调用。 */
    public void prepareClosedPeriods() {
        SystemReportSettingsDto config = settings();
        if (!config.automaticEnabled()) return;
        LocalDate today = LocalDate.now(SHANGHAI);
        for (String period : config.periods()) {
            generateIfMissing(resolvePeriod(period, null, today), config);
        }
    }

    /** 每分钟调度探测一次；只在持久化设置命中的分钟幂等刷新所选最近完整周期。 */
    public boolean prepareScheduledReports(LocalDateTime now) {
        SystemReportSettingsDto config = settings();
        if (!config.automaticEnabled() || !scheduleDue(config, now)) return false;
        for (String period : config.periods()) {
            generate(resolvePeriod(period, null, now.toLocalDate()), config);
        }
        return true;
    }

    private void generateIfMissing(PeriodRange range, SystemReportSettingsDto config) {
        long existing = reports.selectCount(new LambdaQueryWrapper<SystemPeriodicReportEntity>()
                .eq(SystemPeriodicReportEntity::getPeriodType, range.type())
                .eq(SystemPeriodicReportEntity::getPeriodKey, range.key()));
        if (existing == 0) generate(range, config);
    }

    private SystemPeriodicReportDto generate(PeriodRange range, SystemReportSettingsDto config) {
        LocalDateTime from = range.start().atStartOfDay();
        LocalDateTime to = range.end().plusDays(1).atStartOfDay();
        boolean includeCreated = config.aggregationOptions().contains("TASK_CREATED");
        boolean includeCompleted = config.aggregationOptions().contains("TASK_COMPLETED");
        boolean includeRuns = config.aggregationOptions().contains("AI_RUN");

        List<ProjectEntity> projectRows = projects.selectList(null);
        Map<String, String> projectNames = new HashMap<>();
        projectRows.forEach(project -> projectNames.put(project.getId(), project.getName()));

        List<TaskEntity> periodTasks = includeCreated || includeCompleted
                ? tasks.selectList(new LambdaQueryWrapper<TaskEntity>()
                        .and(query -> {
                            if (includeCreated) {
                                query.ge(TaskEntity::getCreatedAt, from).lt(TaskEntity::getCreatedAt, to);
                            }
                            if (includeCompleted) {
                                if (includeCreated) query.or();
                                query.ge(TaskEntity::getCompletedAt, from).lt(TaskEntity::getCompletedAt, to);
                            }
                        })
                        .orderByDesc(TaskEntity::getCreatedAt))
                : List.of();
        List<AiTaskRunEntity> periodRuns = includeRuns
                ? runs.selectList(new LambdaQueryWrapper<AiTaskRunEntity>()
                        .ge(AiTaskRunEntity::getCreatedAt, from).lt(AiTaskRunEntity::getCreatedAt, to)
                        .orderByDesc(AiTaskRunEntity::getCreatedAt))
                : List.of();

        Map<String, TaskEntity> taskById = new HashMap<>();
        for (TaskEntity task : periodTasks) taskById.put(task.getId(), task);
        List<String> missingTaskIds = periodRuns.stream().map(AiTaskRunEntity::getTaskId)
                .filter(id -> id != null && !taskById.containsKey(id)).distinct().toList();
        if (!missingTaskIds.isEmpty()) {
            tasks.selectBatchIds(missingTaskIds).forEach(task -> taskById.put(task.getId(), task));
        }

        List<SystemReportTaskDto> taskSources = periodTasks.stream().map(task -> {
            List<String> events = new ArrayList<>(2);
            if (includeCreated && inside(task.getCreatedAt(), from, to)) events.add("CREATED");
            if (includeCompleted && inside(task.getCompletedAt(), from, to)) events.add("COMPLETED");
            return new SystemReportTaskDto(task.getId(), task.getTitle(), task.getProjectId(),
                    projectNames.get(task.getProjectId()), task.getStatus(), List.copyOf(events),
                    task.getCreatedAt(), task.getCompletedAt());
        }).toList();
        List<SystemReportRunDto> runSources = periodRuns.stream().map(run -> {
            TaskEntity task = taskById.get(run.getTaskId());
            return new SystemReportRunDto(run.getId(), run.getTaskId(), task == null ? null : task.getTitle(),
                    run.getProjectId(), projectNames.get(run.getProjectId()), run.getEngine(), run.getStatus(),
                    limit(run.getResultSummary(), 500), run.getTotalTokens(), run.getCreatedAt(), run.getFinishedAt());
        }).toList();

        SystemPeriodicReportEntity entity = new SystemPeriodicReportEntity();
        entity.setId("sr" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        entity.setPeriodType(range.type());
        entity.setPeriodKey(range.key());
        entity.setPeriodStart(range.start());
        entity.setPeriodEnd(range.end());
        entity.setProjectTotal(projectRows.size());
        entity.setTaskTotal(Math.toIntExact(tasks.selectCount(null)));
        entity.setOpenTaskTotal(Math.toIntExact(tasks.selectCount(new LambdaQueryWrapper<TaskEntity>()
                .ne(TaskEntity::getStatus, TaskService.DONE))));
        entity.setAiRunTotal(Math.toIntExact(runs.selectCount(null)));
        entity.setTaskCreatedCount((int) periodTasks.stream().filter(task -> inside(task.getCreatedAt(), from, to)).count());
        entity.setTaskCompletedCount((int) periodTasks.stream().filter(task -> inside(task.getCompletedAt(), from, to)).count());
        entity.setAiRunCount(periodRuns.size());
        entity.setAiRunSuccessCount((int) periodRuns.stream().filter(run -> "succeeded".equals(run.getStatus())).count());
        entity.setAiRunFailureCount((int) periodRuns.stream().filter(run -> "failed".equals(run.getStatus())).count());
        entity.setAiRunMeteredCount((int) periodRuns.stream().filter(run -> run.getTotalTokens() != null).count());
        entity.setTotalTokens(periodRuns.stream().map(AiTaskRunEntity::getTotalTokens)
                .filter(value -> value != null).reduce(0L, Long::sum));
        entity.setTaskSourcesJson(write(taskSources));
        entity.setRunSourcesJson(write(runSources));
        entity.setAggregationOptionsJson(write(config.aggregationOptions()));
        entity.setGeneratedAt(LocalDateTime.now(SHANGHAI));
        reports.upsert(entity);
        return toDto(entity);
    }

    static boolean scheduleDue(SystemReportSettingsDto config, LocalDateTime now) {
        LocalTime refreshTime = LocalTime.parse(config.refreshTime(), DateTimeFormatter.ofPattern("HH:mm"));
        long anchorMinutes = refreshTime.getHour() * 60L + refreshTime.getMinute();
        long currentMinutes = now.toLocalDate().toEpochDay() * 1_440L
                + now.getHour() * 60L + now.getMinute();
        return Math.floorMod(currentMinutes - anchorMinutes, config.refreshFrequencyHours() * 60L) == 0;
    }

    static PeriodRange resolvePeriod(String raw, LocalDate anchorDate, LocalDate today) {
        String type = raw == null ? "DAY" : raw.trim().toUpperCase(Locale.ROOT);
        LocalDate anchor;
        LocalDate start;
        LocalDate end;
        String key;
        switch (type) {
            case "DAY" -> {
                anchor = anchorDate == null ? today.minusDays(1) : anchorDate;
                start = anchor;
                end = anchor;
                key = DAY_KEY.format(start);
            }
            case "WEEK" -> {
                anchor = anchorDate == null ? today.minusWeeks(1) : anchorDate;
                start = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                end = start.plusDays(6);
                key = start.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR) + "-W"
                        + String.format("%02d", start.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            }
            case "MONTH" -> {
                anchor = anchorDate == null ? today.minusMonths(1) : anchorDate;
                start = anchor.withDayOfMonth(1);
                end = start.with(TemporalAdjusters.lastDayOfMonth());
                key = MONTH_KEY.format(start);
            }
            default -> throw new BadRequestException("报告周期只支持 DAY、WEEK、MONTH");
        }
        if (!end.isBefore(today)) throw new BadRequestException("只能生成或读取已经结束的完整周期");
        return new PeriodRange(type, key, start, end);
    }

    private SystemPeriodicReportDto empty(PeriodRange range, SystemReportSettingsDto config) {
        return new SystemPeriodicReportDto(range.type(), range.key(), label(range), range.start(), range.end(),
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, List.of(), List.of(),
                config.aggregationOptions(), null, "该周期尚未产出，可立即用系统真实数据补齐");
    }

    private SystemPeriodicReportDto toDto(SystemPeriodicReportEntity entity) {
        PeriodRange range = new PeriodRange(entity.getPeriodType(), entity.getPeriodKey(),
                entity.getPeriodStart(), entity.getPeriodEnd());
        return new SystemPeriodicReportDto(entity.getPeriodType(), entity.getPeriodKey(), label(range),
                entity.getPeriodStart(), entity.getPeriodEnd(), entity.getProjectTotal(), entity.getTaskTotal(),
                entity.getOpenTaskTotal(), entity.getAiRunTotal(), entity.getTaskCreatedCount(),
                entity.getTaskCompletedCount(), entity.getAiRunCount(), entity.getAiRunSuccessCount(),
                entity.getAiRunFailureCount(), entity.getAiRunMeteredCount(), entity.getTotalTokens(),
                read(entity.getTaskSourcesJson(), TASK_LIST), read(entity.getRunSourcesJson(), RUN_LIST),
                readAggregations(entity.getAggregationOptionsJson()), entity.getGeneratedAt(),
                "统计来自任务与 AI Run 持久化数据，未回传 Token 的运行未冒充零用量");
    }

    private SystemReportSettingsDto canonicalSettings(boolean automaticEnabled, String refreshTime,
            int refreshFrequencyHours, List<String> periods, List<String> aggregationOptions) {
        String time = refreshTime == null ? "" : refreshTime.trim();
        try {
            time = LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm")).format(DateTimeFormatter.ofPattern("HH:mm"));
        } catch (DateTimeParseException exception) {
            throw new BadRequestException("刷新时间必须使用 HH:mm 格式");
        }
        if (!REFRESH_FREQUENCIES.contains(refreshFrequencyHours)) {
            throw new BadRequestException("刷新频率只支持 1、3、6、12 或 24 小时");
        }
        List<String> normalizedPeriods = canonicalOptions(periods, ALL_PERIODS, "至少选择一种自动报告类型");
        List<String> normalizedAggregations = canonicalOptions(
                aggregationOptions, ALL_AGGREGATIONS, "至少选择一种报告汇总项");
        return new SystemReportSettingsDto(automaticEnabled, time, refreshFrequencyHours,
                normalizedPeriods, normalizedAggregations);
    }

    private List<String> canonicalOptions(List<String> raw, List<String> allowed, String emptyMessage) {
        if (raw == null) throw new BadRequestException(emptyMessage);
        List<String> normalized = allowed.stream().filter(raw::contains).toList();
        if (normalized.isEmpty()) throw new BadRequestException(emptyMessage);
        if (raw.stream().anyMatch(item -> item == null || !allowed.contains(item))
                || raw.stream().distinct().count() != raw.size()) {
            throw new BadRequestException("报告设置包含不支持或重复的选项");
        }
        return normalized;
    }

    private List<String> readAggregations(String raw) {
        if (raw == null || raw.isBlank()) return ALL_AGGREGATIONS;
        try {
            List<String> value = json.readValue(raw, STRING_LIST);
            return canonicalOptions(value, ALL_AGGREGATIONS, "报告快照没有汇总项");
        } catch (RuntimeException | JsonProcessingException exception) {
            throw new IllegalStateException("系统报告汇总项快照损坏", exception);
        }
    }

    private String label(PeriodRange range) {
        if ("DAY".equals(range.type())) return range.start() + " 日报";
        if ("MONTH".equals(range.type())) return MONTH_KEY.format(range.start()) + " 月报";
        return range.start() + " 至 " + range.end() + " 周报";
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("系统报告序列化失败", exception); }
    }

    private <T> T read(String raw, TypeReference<T> type) {
        try { return json.readValue(raw, type); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("系统报告存档损坏", exception); }
    }

    private static boolean inside(LocalDateTime value, LocalDateTime from, LocalDateTime to) {
        return value != null && !value.isBefore(from) && value.isBefore(to);
    }

    private static String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max) + "…";
    }

    record PeriodRange(String type, String key, LocalDate start, LocalDate end) { }
}
