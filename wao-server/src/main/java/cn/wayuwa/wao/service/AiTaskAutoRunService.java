package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.AiTaskAutoRunStateEntity;
import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.AiRunDto;
import cn.wayuwa.wao.dto.AiTaskAutoRunPolicyDto;
import cn.wayuwa.wao.mapper.AiTaskAutoRunStateMapper;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 空闲达到人工阈值后，把录入最早的真实待办以 Plan 模式交给现有 AI Run。 */
@Service
public class AiTaskAutoRunService {

    private static final Logger log = LoggerFactory.getLogger(AiTaskAutoRunService.class);
    private static final String STATE_ID = "oldest-task";
    private static final int DEFAULT_IDLE_HOURS = 2;
    private static final int MIN_IDLE_HOURS = 1;
    private static final int MAX_IDLE_HOURS = 168;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final AiTaskAutoRunStateMapper stateMapper;
    private final AiTaskRunMapper runMapper;
    private final TaskMapper taskMapper;
    private final AiRunService runs;
    private final String publicBaseUrl;

    public AiTaskAutoRunService(AiTaskAutoRunStateMapper stateMapper, AiTaskRunMapper runMapper,
            TaskMapper taskMapper, AiRunService runs,
            @Value("${wao.share.base-url:}") String configuredBaseUrl) {
        this.stateMapper = stateMapper;
        this.runMapper = runMapper;
        this.taskMapper = taskMapper;
        this.runs = runs;
        this.publicBaseUrl = configuredBaseUrl == null || configuredBaseUrl.isBlank()
                ? null : configuredBaseUrl.replaceFirst("/+$", "");
    }

    /** 返回人工设置、最近 AI 活动和本轮检查结果。 */
    public AiTaskAutoRunPolicyDto overview() {
        AiTaskAutoRunStateEntity state = requireState();
        LocalDateTime now = LocalDateTime.now(ZONE);
        LocalDateTime lastActivity = runMapper.latestActivityAt();
        LocalDateTime eligibleAt = lastActivity == null ? now : lastActivity.plusHours(idleHours(state));
        boolean active = hasActiveRun();
        String status;
        String message;
        if (!Boolean.TRUE.equals(state.getEnabled())) {
            status = "disabled";
            message = "自动派发已关闭，待办任务只会由你手动启动。";
        } else if (active) {
            status = "busy";
            message = "当前已有 AI Run，结束后会重新计算空闲时间。";
        } else if (eligibleAt.isAfter(now)) {
            status = "cooling_down";
            message = "距离最近一次 AI Run 尚不足 " + idleHours(state) + " 小时。";
        } else if (taskMapper.selectOldestAutoRunCandidate() == null) {
            status = "no_task";
            message = "当前没有已接入 AI 工作流的待办任务。";
        } else {
            status = "ready";
            message = "空闲达到 " + idleHours(state) + " 小时后，将自动以 Plan 模式执行录入最早的待办。";
        }
        if (state.getLastOutcome() != null && !state.getLastOutcome().isBlank()) {
            message += " 最近检查：" + state.getLastOutcome();
        }
        return new AiTaskAutoRunPolicyDto(Boolean.TRUE.equals(state.getEnabled()), idleHours(state), status,
                message, text(lastActivity), text(eligibleAt), text(state.getLastCheckedAt()), state.getLastRunId());
    }

    /**
     * 保存开关和空闲小时数；保存后立即进入一次真实条件检查。
     *
     * @param enabled 是否允许无人值守派发
     * @param idleHours 距离最近 AI Run 的最小空闲小时数
     */
    public void save(boolean enabled, int idleHours) {
        if (idleHours < MIN_IDLE_HOURS || idleHours > MAX_IDLE_HOURS) {
            throw new BadRequestException("空闲时长必须在 1 到 168 小时之间");
        }
        int updated = stateMapper.update(null, new LambdaUpdateWrapper<AiTaskAutoRunStateEntity>()
                .eq(AiTaskAutoRunStateEntity::getId, STATE_ID)
                .set(AiTaskAutoRunStateEntity::getEnabled, enabled)
                .set(AiTaskAutoRunStateEntity::getIdleHours, idleHours)
                .set(AiTaskAutoRunStateEntity::getNextCheckAt, LocalDateTime.now(ZONE))
                .set(AiTaskAutoRunStateEntity::getLastOutcome,
                        enabled ? "已开启，空闲阈值为 " + idleHours + " 小时" : "已人工关闭")
                .set(AiTaskAutoRunStateEntity::getUpdatedAt, LocalDateTime.now(ZONE)));
        if (updated == 0) throw new IllegalStateException("待办自动派发策略不存在");
    }

    /** 双节点安全地抢占检查窗口，并在满足条件时派发唯一一条最早待办。 */
    public void checkAndLaunch() {
        if (stateMapper.claimDueCheck() == 0) return;
        AiTaskAutoRunStateEntity state = requireState();
        LocalDateTime now = LocalDateTime.now(ZONE);
        try {
            if (hasActiveRun()) {
                outcome("已有 AI Run，本轮跳过", null);
                return;
            }
            LocalDateTime lastActivity = runMapper.latestActivityAt();
            if (lastActivity != null && lastActivity.plusHours(idleHours(state)).isAfter(now)) {
                outcome("距离最近一次 AI Run 尚不足 " + idleHours(state) + " 小时", null);
                return;
            }
            if (publicBaseUrl == null) {
                outcome("未配置公网任务分享地址，无法安全自动派发", null);
                return;
            }
            TaskEntity candidate = taskMapper.selectOldestAutoRunCandidate();
            if (candidate == null) {
                outcome("没有已接入 AI 工作流的待办任务", null);
                return;
            }
            List<AiRunDto> created = runs.enqueue(List.of(candidate.getId()), null, null, null,
                    null, null, null, true, "system", publicBaseUrl);
            if (created.isEmpty()) {
                outcome("最早待办已被其他操作派发，本轮未重复入队", null);
                return;
            }
            AiRunDto run = created.get(0);
            outcome("已用 Plan 模式自动派发「" + shorten(candidate.getTitle()) + "」", run.id());
        } catch (RuntimeException ex) {
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            outcome("自动派发失败：" + shorten(reason), null);
            log.warn("最早待办自动派发失败：{}", reason);
        }
    }

    private boolean hasActiveRun() {
        Long count = runMapper.selectCount(new LambdaQueryWrapper<AiTaskRunEntity>()
                .in(AiTaskRunEntity::getStatus, "queued", "claimed", "running"));
        return count != null && count > 0;
    }

    private void outcome(String message, String runId) {
        LambdaUpdateWrapper<AiTaskAutoRunStateEntity> update = new LambdaUpdateWrapper<AiTaskAutoRunStateEntity>()
                .eq(AiTaskAutoRunStateEntity::getId, STATE_ID)
                .set(AiTaskAutoRunStateEntity::getLastOutcome, shorten(message))
                .set(AiTaskAutoRunStateEntity::getUpdatedAt, LocalDateTime.now(ZONE));
        if (runId != null) update.set(AiTaskAutoRunStateEntity::getLastRunId, runId);
        stateMapper.update(null, update);
    }

    private AiTaskAutoRunStateEntity requireState() {
        AiTaskAutoRunStateEntity state = stateMapper.selectById(STATE_ID);
        if (state == null) throw new IllegalStateException("待办自动派发策略不存在");
        return state;
    }

    private static int idleHours(AiTaskAutoRunStateEntity state) {
        return state.getIdleHours() == null ? DEFAULT_IDLE_HOURS : state.getIdleHours();
    }

    private static String text(LocalDateTime value) {
        return value == null ? null : value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private static String shorten(String value) {
        if (value == null) return "未知错误";
        String normalized = value.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 237) + "...";
    }
}
