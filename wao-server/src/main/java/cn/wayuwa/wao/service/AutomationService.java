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
import cn.wayuwa.wao.dto.CreateJobRequest;
import cn.wayuwa.wao.dto.PatchJobRequest;
import cn.wayuwa.wao.mapper.AutomationJobMapper;
import cn.wayuwa.wao.mapper.AutomationRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final TypeReference<List<String>> STEPS_TYPE = new TypeReference<>() { };
    private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("今天 HH:mm");

    private final AutomationJobMapper jobMapper;
    private final AutomationRunMapper runMapper;
    private final ObjectMapper json;

    public AutomationService(AutomationJobMapper jobMapper, AutomationRunMapper runMapper, ObjectMapper json) {
        this.jobMapper = jobMapper;
        this.runMapper = runMapper;
        this.json = json;
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
        jobMapper.updateById(e);
        return getJob(id);
    }

    /** 删除自动化任务，级联删除其运行记录。 */
    public void deleteJob(String id) {
        requireJob(id);
        runMapper.delete(new LambdaQueryWrapper<AutomationRunEntity>().eq(AutomationRunEntity::getJobId, id));
        jobMapper.deleteById(id);
    }

    /** 手动触发一次运行；「待修复」状态的任务会运行失败并提示需要先重新授权。 */
    public AutomationJobDto runJob(String id) {
        AutomationJobEntity e = requireJob(id);
        boolean broken = "待修复".equals(e.getStatus());
        recordRun(e, broken ? "失败 · 需要先重新授权" : "成功 · 手动触发");
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
        recordRun(e, "成功 · 自动执行");
        e.setNextFireAt(computeNextFire(e.getCronExpr()));
        jobMapper.updateById(e);
    }

    /** 查询已到期且需要自动执行的任务（cronExpr 非空、未暂停、到期）。供调度器轮询使用。 */
    public List<AutomationJobEntity> dueJobs(LocalDateTime now) {
        return jobMapper.selectList(new LambdaQueryWrapper<AutomationJobEntity>()
                .isNotNull(AutomationJobEntity::getCronExpr)
                .ne(AutomationJobEntity::getStatus, "已暂停")
                .le(AutomationJobEntity::getNextFireAt, now));
    }

    private void recordRun(AutomationJobEntity e, String result) {
        AutomationRunEntity run = new AutomationRunEntity();
        run.setId("r" + System.currentTimeMillis());
        run.setJobId(e.getId());
        run.setRunTimeLabel(LocalDateTime.now().format(TIME_LABEL));
        run.setResult(result);
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
        List<List<String>> runPairs = runs.stream()
                .map(r -> List.of(r.getRunTimeLabel(), r.getResult()))
                .toList();
        return new AutomationJobDto(e.getId(), e.getName(), e.getIcon(), e.getScheduleLabel(), e.getStatus(),
                e.getLastRunLabel(), e.getDescription(), e.getSource(), e.getTarget(), readSteps(e.getStepsJson()),
                runPairs);
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
