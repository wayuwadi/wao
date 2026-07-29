package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.AutomationJobEntity;
import cn.wayuwa.wao.domain.AutomationRunEntity;
import cn.wayuwa.wao.dto.AutomationJobDto;
import cn.wayuwa.wao.dto.AutomationRunDto;
import cn.wayuwa.wao.dto.CreateJobRequest;
import cn.wayuwa.wao.dto.PatchJobRequest;
import cn.wayuwa.wao.mapper.AutomationJobMapper;
import cn.wayuwa.wao.mapper.AutomationRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

/**
 * 自动化域服务：任务的增删改查、手动触发、暂停 / 恢复 / 重新授权。
 *
 * <p>steps 为描述性文本（拉取 / 聚类 / 写入等），本轮不接入真实执行引擎（飞书 / NAS / 邮箱等外部系统集成
 * 超出当前范围），运行记录由模拟执行产生；但 {@code cronExpr} 非空的任务会被 {@link JobSchedulerService}
 * 按真实 Cron 表达式周期性触发，而非仅停留在展示态。
 */
@Service
public class AutomationService {

    private static final Logger log = LoggerFactory.getLogger(AutomationService.class);
    private static final TypeReference<List<String>> STEPS_TYPE = new TypeReference<>() { };
    private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("今天 HH:mm");

    private final AutomationJobMapper jobMapper;
    private final AutomationRunMapper runMapper;
    private final ObjectMapper json;
    /** 已登记的真实执行器，按 {@link JobHandler#name()} 索引。 */
    private final Map<String, JobHandler> handlers;
    private final MemberService members;

    public AutomationService(AutomationJobMapper jobMapper, AutomationRunMapper runMapper, ObjectMapper json,
            List<JobHandler> handlers, MemberService members) {
        this.jobMapper = jobMapper;
        this.runMapper = runMapper;
        this.json = json;
        this.handlers = handlers.stream().collect(Collectors.toMap(JobHandler::name, h -> h));
        this.members = members;
    }

    /** 列出全部自动化任务（含近期运行记录，最近一次在前）。 */
    public List<AutomationJobDto> listJobs() {
        List<AutomationJobEntity> jobs = jobMapper.selectList(
                new LambdaQueryWrapper<AutomationJobEntity>().orderByAsc(AutomationJobEntity::getOrd));
        List<AutomationRunEntity> runs = runMapper.selectList(
                new LambdaQueryWrapper<AutomationRunEntity>().orderByAsc(AutomationRunEntity::getOrd));
        Map<String, List<AutomationRunEntity>> runsByJob = runs.stream()
                .collect(Collectors.groupingBy(AutomationRunEntity::getJobId));
        return jobs.stream().map(j -> toDto(j, runsByJob.getOrDefault(j.getId(), List.of()))).toList();
    }

    /** 按 id 获取任务详情。 */
    public AutomationJobDto getJob(String id) {
        AutomationJobEntity e = requireJob(id);
        List<AutomationRunEntity> runs = runMapper.selectList(new LambdaQueryWrapper<AutomationRunEntity>()
                .eq(AutomationRunEntity::getJobId, id).orderByAsc(AutomationRunEntity::getOrd));
        return toDto(e, runs);
    }

    /** 新建自动化任务。 */
    public AutomationJobDto createJob(CreateJobRequest req) {
        validateCron(req.cronExpr());
        AutomationJobEntity e = new AutomationJobEntity();
        e.setId("j" + System.currentTimeMillis());
        e.setName(req.name().trim());
        e.setIcon(blankOr(req.icon(), "ri-flashlight-line"));
        e.setScheduleLabel(blankOr(req.schedule(), req.cronExpr() != null ? req.cronExpr() : "仅手动触发"));
        e.setCronExpr(req.cronExpr());
        e.setStatus("正常");
        e.setLastRunLabel("尚未运行");
        e.setDescription(req.desc());
        e.setSource(req.source());
        e.setTarget(req.target());
        e.setStepsJson(write(req.steps() == null ? List.of() : req.steps()));
        e.setNextFireAt(computeNextFire(req.cronExpr()));
        e.setHandler(req.handler());
        e.setOrd(nextOrd(jobMapper.selectCount(null)));
        jobMapper.insert(e);
        return toDto(e, List.of());
    }

    /** 局部更新自动化任务。 */
    public AutomationJobDto updateJob(String id, PatchJobRequest req) {
        AutomationJobEntity e = requireJob(id);
        if (req.name() != null && !req.name().isBlank()) {
            e.setName(req.name().trim());
        }
        if (req.icon() != null && !req.icon().isBlank()) {
            e.setIcon(req.icon());
        }
        if (req.schedule() != null && !req.schedule().isBlank()) {
            e.setScheduleLabel(req.schedule());
        }
        if (req.desc() != null) {
            e.setDescription(req.desc());
        }
        if (req.source() != null) {
            e.setSource(req.source());
        }
        if (req.target() != null) {
            e.setTarget(req.target());
        }
        if (req.steps() != null) {
            e.setStepsJson(write(req.steps()));
        }
        if (req.cronExpr() != null) {
            validateCron(req.cronExpr());
            String cron = req.cronExpr().isBlank() ? null : req.cronExpr();
            e.setCronExpr(cron);
            e.setNextFireAt(computeNextFire(cron));
        }
        if (req.handler() != null) e.setHandler(req.handler().isBlank() ? null : req.handler());
        jobMapper.updateById(e);
        return getJob(id);
    }

    /** 删除自动化任务，级联删除其运行记录。 */
    public void deleteJob(String id) {
        requireJob(id);
        runMapper.delete(new LambdaQueryWrapper<AutomationRunEntity>().eq(AutomationRunEntity::getJobId, id));
        jobMapper.deleteById(id);
    }

    /**
     * 手动触发一次运行；「待修复」状态的任务会运行失败并提示需要先重新授权。
     *
     * <p>登记了真实执行器（{@code handler}）的任务会真的去跑一遍，运行记录写的是执行器返回的结果；
     * 没有执行器的仍是模拟运行。
     */
    public AutomationJobDto runJob(String id, String executorId) {
        AutomationJobEntity e = requireJob(id);
        if ("待修复".equals(e.getStatus())) {
            recordRun(e, "失败 · 需要先重新授权", executorId);
        } else {
            recordRun(e, execute(e, "成功 · 手动触发"), executorId);
        }
        jobMapper.updateById(e);
        return getJob(id);
    }

    /** 暂停任务：停止按计划自动执行，可仍手动触发。 */
    public AutomationJobDto pauseJob(String id) {
        AutomationJobEntity e = requireJob(id);
        e.setStatus("已暂停");
        jobMapper.updateById(e);
        return getJob(id);
    }

    /** 恢复已暂停的任务。 */
    public AutomationJobDto resumeJob(String id) {
        AutomationJobEntity e = requireJob(id);
        e.setStatus("正常");
        if (e.getCronExpr() != null) {
            e.setNextFireAt(computeNextFire(e.getCronExpr()));
        }
        jobMapper.updateById(e);
        return getJob(id);
    }

    /** 重新授权：把「待修复」任务恢复为「正常」。 */
    public AutomationJobDto reauthJob(String id) {
        AutomationJobEntity e = requireJob(id);
        e.setStatus("正常");
        if (e.getCronExpr() != null) {
            e.setNextFireAt(computeNextFire(e.getCronExpr()));
        }
        jobMapper.updateById(e);
        return getJob(id);
    }

    /** 供 {@link JobSchedulerService} 调用：按 Cron 到期自动执行一次，并推进下一次触发时间。 */
    public void executeScheduled(AutomationJobEntity e) {
        LocalDateTime nextFireAt = computeNextFire(e.getCronExpr());
        int claimed = jobMapper.update(null, new LambdaUpdateWrapper<AutomationJobEntity>()
                .eq(AutomationJobEntity::getId, e.getId())
                .eq(AutomationJobEntity::getNextFireAt, e.getNextFireAt())
                .set(AutomationJobEntity::getNextFireAt, nextFireAt));
        if (claimed == 0) {
            return;
        }
        e.setNextFireAt(nextFireAt);
        recordRun(e, execute(e, "成功 · 自动执行"), null);
        jobMapper.updateById(e);
    }

    /**
     * 跑一次任务本体：有真实执行器就调它，否则按模拟执行返回默认文案。
     *
     * <p>执行器抛异常不往上传 —— 一次失败只该变成一条「失败 · 原因」的运行记录，
     * 不该让手动触发接口 500，也不该中断调度器对其它任务的轮询。
     *
     * @param e            任务
     * @param simulatedResult 没有执行器时写进运行记录的文案
     * @return 运行结果文案
     */
    private String execute(AutomationJobEntity e, String simulatedResult) {
        JobHandler handler = e.getHandler() == null ? null : handlers.get(e.getHandler());
        if (handler == null) {
            return simulatedResult;
        }
        try {
            return handler.execute(e);
        } catch (RuntimeException ex) {
            log.warn("自动化任务 {} 执行失败：{}", e.getId(), ex.getMessage());
            return "失败 · " + shortReason(ex);
        }
    }

    /** 运行记录那一列只有 64 字符，失败原因取前一段就够定位。 */
    private static String shortReason(RuntimeException ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return message.length() <= 48 ? message : message.substring(0, 48) + "…";
    }

    /** 查询已到期且需要自动执行的任务（cronExpr 非空、未暂停、到期）。供调度器轮询使用。 */
    public List<AutomationJobEntity> dueJobs(LocalDateTime now) {
        return jobMapper.selectList(new LambdaQueryWrapper<AutomationJobEntity>()
                .isNotNull(AutomationJobEntity::getCronExpr)
                .ne(AutomationJobEntity::getStatus, "已暂停")
                .le(AutomationJobEntity::getNextFireAt, now));
    }

    private void recordRun(AutomationJobEntity e, String result, String executorId) {
        AutomationRunEntity run = new AutomationRunEntity();
        run.setId("r" + System.currentTimeMillis());
        run.setJobId(e.getId());
        run.setRunTimeLabel(LocalDateTime.now().format(TIME_LABEL));
        run.setResult(result);
        run.setExecutorId(executorId);
        int stepCount = readSteps(e.getStepsJson()).size();
        run.setDurationMs(800 + stepCount * 1200);
        run.setOrd(0);
        runMapper.insert(run);
        // 让新记录排在最前（最近一次在前），其余顺延。
        runMapper.selectList(new LambdaQueryWrapper<AutomationRunEntity>()
                        .eq(AutomationRunEntity::getJobId, e.getId()).ne(AutomationRunEntity::getId, run.getId())
                        .orderByAsc(AutomationRunEntity::getOrd))
                .forEach(r -> {
                    r.setOrd(r.getOrd() + 1);
                    runMapper.updateById(r);
                });
        e.setLastRunLabel(run.getRunTimeLabel() + " · " + result);
    }

    private AutomationJobEntity requireJob(String id) {
        AutomationJobEntity e = jobMapper.selectById(id);
        if (e == null) {
            throw new NotFoundException("自动化任务不存在：" + id);
        }
        return e;
    }

    private LocalDateTime computeNextFire(String cronExpr) {
        if (cronExpr == null || cronExpr.isBlank()) {
            return null;
        }
        return CronExpression.parse(cronExpr).next(LocalDateTime.now());
    }

    private void validateCron(String cronExpr) {
        if (cronExpr != null && !cronExpr.isBlank()) {
            CronExpression.parse(cronExpr);
        }
    }

    private AutomationJobDto toDto(AutomationJobEntity e, List<AutomationRunEntity> runs) {
        List<AutomationRunDto> runPairs = runs.stream()
                .map(r -> new AutomationRunDto(r.getRunTimeLabel(), executorName(r.getExecutorId()), r.getResult()))
                .toList();
        return new AutomationJobDto(e.getId(), e.getName(), e.getIcon(), e.getScheduleLabel(), e.getStatus(),
                e.getLastRunLabel(), e.getDescription(), e.getSource(), e.getTarget(), readSteps(e.getStepsJson()),
                runPairs, e.getHandler());
    }

    private String executorName(String executorId) {
        if (executorId == null || executorId.isBlank()) return "定时任务";
        return members.list().stream().filter(member -> executorId.equals(member.id()))
                .map(member -> member.name()).findFirst().orElse(executorId);
    }

    private List<String> readSteps(String value) {
        if (value == null || value.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return json.readValue(value, STEPS_TYPE);
        } catch (JsonProcessingException ex) {
            return new ArrayList<>();
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int nextOrd(Long existingCount) {
        return (existingCount == null ? 0 : existingCount.intValue()) + 1;
    }
}
