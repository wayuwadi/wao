package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.AiTaskAutoRunStateEntity;
import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.AiRunDto;
import cn.wayuwa.wao.dto.AiTaskAutoRunPolicyDto;
import cn.wayuwa.wao.dto.AiProjectConfigDto;
import cn.wayuwa.wao.mapper.AiTaskAutoRunStateMapper;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final String DEFAULT_PROJECT_NAME = "Wa All in One";
    private static final List<String> DEFAULT_TASK_STATUSES = List.of("待办");
    private static final Set<String> ALLOWED_TASK_STATUSES = Set.of("待办", "收集中", "进行中");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final AiTaskAutoRunStateMapper stateMapper;
    private final AiTaskRunMapper runMapper;
    private final TaskMapper taskMapper;
    private final ProjectMapper projectMapper;
    private final AiRunService runs;
    private final FeishuTaskNotifier notifier;
    private final FeishuWebhookConfigService webhooks;
    private final NetworkOutageWindowService networkOutageWindow;
    private final ObjectMapper json;
    private final String publicBaseUrl;

    public AiTaskAutoRunService(AiTaskAutoRunStateMapper stateMapper, AiTaskRunMapper runMapper,
            TaskMapper taskMapper, ProjectMapper projectMapper, AiRunService runs, FeishuTaskNotifier notifier,
            FeishuWebhookConfigService webhooks, NetworkOutageWindowService networkOutageWindow, ObjectMapper json,
            @Value("${wao.share.base-url:}") String configuredBaseUrl) {
        this.stateMapper = stateMapper;
        this.runMapper = runMapper;
        this.taskMapper = taskMapper;
        this.projectMapper = projectMapper;
        this.runs = runs;
        this.notifier = notifier;
        this.webhooks = webhooks;
        this.networkOutageWindow = networkOutageWindow;
        this.json = json;
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
        ProjectEntity project = configuredProject(state);
        List<String> taskStatuses = taskStatuses(state);
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
        } else if (project == null) {
            status = "invalid_config";
            message = "自动派发项目不存在或未接入可用的 AI 工作流，请重新选择。";
        } else if (taskMapper.selectOldestAutoRunCandidate(project.getId(), taskStatuses) == null) {
            status = "no_task";
            message = "「" + project.getName() + "」当前没有状态为“"
                    + String.join(" / ", taskStatuses) + "”的可派发任务。";
        } else {
            status = "ready";
            message = "空闲达到 " + idleHours(state) + " 小时后，将从「" + project.getName()
                    + "」自动以 Plan 模式执行录入最早的匹配任务。";
        }
        if (state.getLastOutcome() != null && !state.getLastOutcome().isBlank()) {
            message += " 最近检查：" + state.getLastOutcome();
        }
        return new AiTaskAutoRunPolicyDto(Boolean.TRUE.equals(state.getEnabled()), idleHours(state),
                project == null ? state.getProjectId() : project.getId(), taskStatuses,
                state.getNotificationWebhookId(), status,
                message, text(lastActivity), text(eligibleAt), text(state.getLastCheckedAt()), state.getLastRunId());
    }

    /**
     * 保存开关和空闲小时数；保存后立即进入一次真实条件检查。
     *
     * @param enabled 是否允许无人值守派发
     * @param idleHours 距离最近 AI Run 的最小空闲小时数
     * @param projectId 只从这个已接入真实工作流的项目选择任务；旧客户端留空时回落默认项目
     * @param taskStatuses 允许进入候选的任务状态；旧客户端留空时回落“待办”
     * @param notificationWebhookId 触发通知目标；留空时沿用系统默认机器人
     */
    public void save(boolean enabled, int idleHours, String projectId, List<String> taskStatuses,
            String notificationWebhookId) {
        if (idleHours < MIN_IDLE_HOURS || idleHours > MAX_IDLE_HOURS) {
            throw new BadRequestException("空闲时长必须在 1 到 168 小时之间");
        }
        ProjectEntity project = requireRunnableProject(projectId);
        List<String> normalizedStatuses = normalizeTaskStatuses(taskStatuses);
        String webhookId = trimToNull(notificationWebhookId);
        if (webhookId != null) webhooks.enabledTarget(webhookId);
        int updated = stateMapper.update(null, new LambdaUpdateWrapper<AiTaskAutoRunStateEntity>()
                .eq(AiTaskAutoRunStateEntity::getId, STATE_ID)
                .set(AiTaskAutoRunStateEntity::getEnabled, enabled)
                .set(AiTaskAutoRunStateEntity::getIdleHours, idleHours)
                .set(AiTaskAutoRunStateEntity::getProjectId, project.getId())
                .set(AiTaskAutoRunStateEntity::getTaskStatusesJson, writeStatuses(normalizedStatuses))
                .set(AiTaskAutoRunStateEntity::getNotificationWebhookId, webhookId)
                .set(AiTaskAutoRunStateEntity::getNextCheckAt, LocalDateTime.now(ZONE))
                .set(AiTaskAutoRunStateEntity::getLastOutcome,
                        enabled ? "已开启：" + project.getName() + " · "
                                + String.join("/", normalizedStatuses) + " · 空闲 " + idleHours + " 小时"
                                : "已人工关闭")
                .set(AiTaskAutoRunStateEntity::getUpdatedAt, LocalDateTime.now(ZONE)));
        if (updated == 0) throw new IllegalStateException("待办自动派发策略不存在");
    }

    /** 双节点安全地抢占检查窗口，并在满足条件时派发唯一一条最早待办。 */
    public void checkAndLaunch() {
        if (networkOutageWindow.isUnavailable()) return;
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
            ProjectEntity project = configuredProject(state);
            if (project == null) {
                outcome("自动派发项目不存在或未接入可用的 AI 工作流", null);
                return;
            }
            List<String> statuses = taskStatuses(state);
            TaskEntity candidate = taskMapper.selectOldestAutoRunCandidate(project.getId(), statuses);
            if (candidate == null) {
                outcome("所选项目没有状态为“" + String.join("/", statuses) + "”的可派发任务", null);
                return;
            }
            List<AiRunDto> created = runs.enqueue(List.of(candidate.getId()), null, null, null,
                    null, null, null, true, AiRunService.IDLE_AUTO_RUN_REQUESTED_BY, publicBaseUrl);
            if (created.isEmpty()) {
                outcome("最早待办已被其他操作派发，本轮未重复入队", null);
                return;
            }
            AiRunDto run = created.get(0);
            boolean notified = notifier.notifyIdleAutoRun(candidate.getId(), project.getId(), candidate.getTitle(),
                    project.getName(), idleHours(state), state.getNotificationWebhookId());
            outcome("已用 Plan 模式自动派发「" + shorten(candidate.getTitle()) + "」"
                    + (notified ? "，闲置触发通知已发送" : "；闲置触发通知未发出，请检查所选通知群"), run.id());
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

    private ProjectEntity configuredProject(AiTaskAutoRunStateEntity state) {
        String projectId = trimToNull(state.getProjectId());
        ProjectEntity project = projectId == null ? defaultProject() : projectMapper.selectById(projectId);
        return runnable(project) ? project : null;
    }

    private ProjectEntity requireRunnableProject(String requestedProjectId) {
        String projectId = trimToNull(requestedProjectId);
        ProjectEntity project = projectId == null ? defaultProject() : projectMapper.selectById(projectId);
        if (project == null) {
            throw new BadRequestException(projectId == null
                    ? "未找到默认项目「" + DEFAULT_PROJECT_NAME + "」，请先选择自动派发项目"
                    : "自动派发项目不存在，请重新选择");
        }
        if (!runnable(project)) throw new BadRequestException("所选项目尚未接入可用的 AI 工作流");
        return project;
    }

    private ProjectEntity defaultProject() {
        return projectMapper.selectList(new LambdaQueryWrapper<ProjectEntity>()
                        .eq(ProjectEntity::getName, DEFAULT_PROJECT_NAME)
                        .orderByAsc(ProjectEntity::getId))
                .stream().findFirst().orElse(null);
    }

    private boolean runnable(ProjectEntity project) {
        if (project == null) return false;
        try {
            AiProjectConfigDto config = runs.getConfig(project.getId());
            return config.enabled() && config.repoUrl() != null && !config.repoUrl().isBlank();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private List<String> taskStatuses(AiTaskAutoRunStateEntity state) {
        String stored = state.getTaskStatusesJson();
        if (stored == null || stored.isBlank()) return DEFAULT_TASK_STATUSES;
        try {
            return normalizeTaskStatuses(json.readValue(stored, new TypeReference<List<String>>() { }));
        } catch (JsonProcessingException | BadRequestException exception) {
            log.warn("最早待办自动派发状态配置损坏，回退为待办");
            return DEFAULT_TASK_STATUSES;
        }
    }

    private static List<String> normalizeTaskStatuses(List<String> statuses) {
        if (statuses == null) return DEFAULT_TASK_STATUSES;
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String status : statuses) {
            String value = status == null ? "" : status.trim();
            if (!ALLOWED_TASK_STATUSES.contains(value)) {
                throw new BadRequestException("自动派发只支持待办、收集中或进行中任务");
            }
            normalized.add(value);
        }
        if (normalized.isEmpty()) throw new BadRequestException("请至少选择一种自动派发任务状态");
        return List.copyOf(normalized);
    }

    private String writeStatuses(List<String> statuses) {
        try {
            return json.writeValueAsString(statuses);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("自动派发任务状态无法保存", exception);
        }
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

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
