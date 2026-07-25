package cn.wayuwa.wao.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.AiProjectConfigEntity;
import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import cn.wayuwa.wao.domain.AiRunnerPairCodeEntity;
import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.AiPairCodeDto;
import cn.wayuwa.wao.dto.AiProjectConfigDto;
import cn.wayuwa.wao.dto.AiRunDto;
import cn.wayuwa.wao.dto.AiRunnerClaimDto;
import cn.wayuwa.wao.dto.AiRunnerDto;
import cn.wayuwa.wao.dto.CompleteAiRunRequest;
import cn.wayuwa.wao.dto.PairedAiRunnerDto;
import cn.wayuwa.wao.dto.PairAiRunnerRequest;
import cn.wayuwa.wao.dto.SaveAiProjectConfigRequest;
import cn.wayuwa.wao.mapper.AiProjectConfigMapper;
import cn.wayuwa.wao.mapper.AiRunnerNodeMapper;
import cn.wayuwa.wao.mapper.AiRunnerPairCodeMapper;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 任务执行中心。
 *
 * <p>WAO 只保存队列、节点心跳和运行结果；Codex 登录态、Git 凭据与实际工作目录始终留在
 * 用户电脑上的 runner 进程中。领取动作串行化，避免两台电脑拿到同一条任务。
 */
@Service
public class AiRunService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PAIR_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final List<String> ACTIVE = List.of("queued", "claimed", "running");
    private static final int MAX_RESULT = 32_000;

    private final AiProjectConfigMapper configMapper;
    private final AiRunnerNodeMapper runnerMapper;
    private final AiRunnerPairCodeMapper pairMapper;
    private final AiTaskRunMapper runMapper;
    private final TaskMapper taskMapper;
    private final ProjectMapper projectMapper;

    public AiRunService(AiProjectConfigMapper configMapper, AiRunnerNodeMapper runnerMapper,
            AiRunnerPairCodeMapper pairMapper, AiTaskRunMapper runMapper, TaskMapper taskMapper,
            ProjectMapper projectMapper) {
        this.configMapper = configMapper;
        this.runnerMapper = runnerMapper;
        this.pairMapper = pairMapper;
        this.runMapper = runMapper;
        this.taskMapper = taskMapper;
        this.projectMapper = projectMapper;
    }

    /** 读取项目配置；尚未保存过时返回安全的关闭状态。 */
    public AiProjectConfigDto getConfig(String projectId) {
        requireProject(projectId);
        AiProjectConfigEntity entity = configMapper.selectById(projectId);
        return entity == null
                ? new AiProjectConfigDto(projectId, false, "", "main", defaultFolder(projectId), null, 2)
                : toConfigDto(entity);
    }

    /** 保存项目配置并校验仓库、分支、目录与并发参数。 */
    public AiProjectConfigDto saveConfig(String projectId, SaveAiProjectConfigRequest request) {
        ProjectEntity project = requireProject(projectId);
        boolean enabled = Boolean.TRUE.equals(request.enabled());
        String repoUrl = trim(request.repoUrl());
        String branch = blankOr(request.defaultBranch(), "main");
        String folder = blankOr(request.workspaceDir(), safeFolder(project.getName(), projectId));
        int maxParallel = request.maxParallelRuns() == null ? 2 : request.maxParallelRuns();
        if (enabled && !StringUtils.hasText(repoUrl)) {
            throw new BadRequestException("启用 AI Run 前请填写 Git 仓库地址");
        }
        if (StringUtils.hasText(repoUrl)
                && !(repoUrl.startsWith("https://") || repoUrl.startsWith("ssh://") || repoUrl.startsWith("git@"))) {
            throw new BadRequestException("Git 仓库地址仅支持 HTTPS 或 SSH");
        }
        if (!branch.matches("[A-Za-z0-9._/-]+") || branch.contains("..")) {
            throw new BadRequestException("默认分支名称不合法");
        }
        if (!folder.matches("[A-Za-z0-9._-]+") || folder.contains("..")) {
            throw new BadRequestException("工程文件夹只能包含字母、数字、点、横线和下划线");
        }
        if (maxParallel < 1 || maxParallel > 10) {
            throw new BadRequestException("项目并发数必须在 1 到 10 之间");
        }
        String preferred = trim(request.preferredRunnerId());
        if (StringUtils.hasText(preferred) && runnerMapper.selectById(preferred) == null) {
            throw new BadRequestException("指定的执行电脑不存在");
        }
        AiProjectConfigEntity entity = configMapper.selectById(projectId);
        if (entity == null) {
            entity = new AiProjectConfigEntity();
            entity.setProjectId(projectId);
        }
        entity.setEnabled(enabled);
        entity.setRepoUrl(repoUrl);
        entity.setDefaultBranch(branch);
        entity.setWorkspaceDir(folder);
        entity.setPreferredRunnerId(StringUtils.hasText(preferred) ? preferred : null);
        entity.setMaxParallelRuns(maxParallel);
        entity.setUpdatedAt(LocalDateTime.now());
        if (configMapper.selectById(projectId) == null) configMapper.insert(entity);
        else configMapper.updateById(entity);
        return toConfigDto(entity);
    }

    /** 创建十分钟有效的一次性配对码。 */
    public AiPairCodeDto createPairCode() {
        String code = randomText(12);
        LocalDateTime now = LocalDateTime.now();
        AiRunnerPairCodeEntity entity = new AiRunnerPairCodeEntity();
        entity.setId(id("pc"));
        entity.setCodeHash(hash(code));
        entity.setCreatedAt(now);
        entity.setExpiresAt(now.plusMinutes(10));
        pairMapper.insert(entity);
        return new AiPairCodeDto(code, entity.getExpiresAt().toString());
    }

    /** 节点用一次性配对码换取长期本机令牌；令牌明文只返回这一次。 */
    public synchronized PairedAiRunnerDto pair(PairAiRunnerRequest request) {
        String codeHash = hash(request.code().trim().toUpperCase());
        AiRunnerPairCodeEntity pair = pairMapper.selectOne(new LambdaQueryWrapper<AiRunnerPairCodeEntity>()
                .eq(AiRunnerPairCodeEntity::getCodeHash, codeHash));
        LocalDateTime now = LocalDateTime.now();
        if (pair == null || pair.getUsedAt() != null || pair.getExpiresAt().isBefore(now)) {
            throw new UnauthorizedException("配对码无效、已使用或已过期");
        }
        String token = randomText(48);
        AiRunnerNodeEntity runner = new AiRunnerNodeEntity();
        runner.setId(id("rn"));
        runner.setName(request.name().trim());
        runner.setPlatform(blankOr(request.platform(), "unknown"));
        runner.setMaxConcurrency(clamp(request.maxConcurrency(), 1, 8, 1));
        runner.setTokenHash(hash(token));
        runner.setCreatedAt(now);
        runner.setLastSeenAt(now);
        runnerMapper.insert(runner);
        pair.setUsedAt(now);
        pairMapper.updateById(pair);
        return new PairedAiRunnerDto(runner.getId(), token);
    }

    /** 列出执行电脑、在线状态与当前占用的槽位。 */
    public List<AiRunnerDto> listRunners() {
        Map<String, Long> activeByRunner = runMapper.selectList(new LambdaQueryWrapper<AiTaskRunEntity>()
                        .in(AiTaskRunEntity::getStatus, List.of("claimed", "running"))
                        .isNotNull(AiTaskRunEntity::getRunnerId))
                .stream().collect(Collectors.groupingBy(AiTaskRunEntity::getRunnerId, Collectors.counting()));
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(2);
        return runnerMapper.selectList(new LambdaQueryWrapper<AiRunnerNodeEntity>()
                        .orderByDesc(AiRunnerNodeEntity::getLastSeenAt))
                .stream().map(r -> new AiRunnerDto(r.getId(), r.getName(), r.getPlatform(),
                        valueOr(r.getMaxConcurrency(), 1), activeByRunner.getOrDefault(r.getId(), 0L).intValue(),
                        r.getLastSeenAt() != null && r.getLastSeenAt().isAfter(cutoff),
                        r.getLastSeenAt() == null ? null : r.getLastSeenAt().toString()))
                .toList();
    }

    /** 把一个或多个任务加入队列；同一任务已有活动运行时不会重复入队。 */
    public synchronized List<AiRunDto> enqueue(List<String> taskIds, String requestedBy, String baseUrl) {
        List<AiTaskRunEntity> created = new ArrayList<>();
        for (String taskId : taskIds.stream().distinct().toList()) {
            TaskEntity task = taskMapper.selectById(taskId);
            if (task == null) throw new NotFoundException("任务不存在：" + taskId);
            AiProjectConfigEntity config = configMapper.selectById(task.getProjectId());
            if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
                throw new BadRequestException("项目尚未开启 AI Run，请先在项目设置中配置");
            }
            if (!StringUtils.hasText(config.getRepoUrl())) {
                throw new BadRequestException("项目尚未配置 Git 仓库地址");
            }
            Long duplicate = runMapper.selectCount(new LambdaQueryWrapper<AiTaskRunEntity>()
                    .eq(AiTaskRunEntity::getTaskId, taskId).in(AiTaskRunEntity::getStatus, ACTIVE));
            if (duplicate != null && duplicate > 0) continue;
            AiTaskRunEntity run = new AiTaskRunEntity();
            run.setId(id("ar"));
            run.setTaskId(taskId);
            run.setProjectId(task.getProjectId());
            run.setStatus("queued");
            run.setRequestedBy(requestedBy);
            run.setPromptUrl(baseUrl + "/api/share/tasks/" + taskId + ".md");
            run.setBranchName("ai/wao-" + run.getId());
            run.setCreatedAt(LocalDateTime.now());
            runMapper.insert(run);
            setTaskStatus(taskId, "AI 进行中");
            created.add(run);
        }
        return toRunDtos(created);
    }

    /** 最近运行；activeOnly 为 true 时只返回排队和运行中的记录。 */
    public List<AiRunDto> listRuns(boolean activeOnly) {
        recoverStaleRuns();
        LambdaQueryWrapper<AiTaskRunEntity> query = new LambdaQueryWrapper<AiTaskRunEntity>()
                .orderByDesc(AiTaskRunEntity::getCreatedAt).last("LIMIT 100");
        if (activeOnly) query.in(AiTaskRunEntity::getStatus, ACTIVE);
        return toRunDtos(runMapper.selectList(query));
    }

    /** 节点心跳；同时刷新并发能力，便于用户调整本机 runner 参数后立即生效。 */
    public AiRunnerDto heartbeat(String token, Integer maxConcurrency) {
        AiRunnerNodeEntity runner = requireRunner(token);
        runner.setLastSeenAt(LocalDateTime.now());
        if (maxConcurrency != null) runner.setMaxConcurrency(clamp(maxConcurrency, 1, 8, 1));
        runnerMapper.updateById(runner);
        return listRunners().stream().filter(r -> r.id().equals(runner.getId())).findFirst().orElseThrow();
    }

    /** 节点领取下一条满足节点偏好和项目并发上限的任务。 */
    public synchronized AiRunnerClaimDto claim(String token) {
        recoverStaleRuns();
        AiRunnerNodeEntity runner = requireRunner(token);
        runner.setLastSeenAt(LocalDateTime.now());
        runnerMapper.updateById(runner);
        long ownActive = countRuns(new LambdaQueryWrapper<AiTaskRunEntity>()
                .eq(AiTaskRunEntity::getRunnerId, runner.getId())
                .in(AiTaskRunEntity::getStatus, List.of("claimed", "running")));
        if (ownActive >= valueOr(runner.getMaxConcurrency(), 1)) return null;

        List<AiTaskRunEntity> queued = runMapper.selectList(new LambdaQueryWrapper<AiTaskRunEntity>()
                .eq(AiTaskRunEntity::getStatus, "queued").orderByAsc(AiTaskRunEntity::getCreatedAt).last("LIMIT 50"));
        for (AiTaskRunEntity run : queued) {
            AiProjectConfigEntity config = configMapper.selectById(run.getProjectId());
            if (config == null || !Boolean.TRUE.equals(config.getEnabled())) continue;
            if (StringUtils.hasText(config.getPreferredRunnerId())
                    && !runner.getId().equals(config.getPreferredRunnerId())) continue;
            long projectActive = countRuns(new LambdaQueryWrapper<AiTaskRunEntity>()
                    .eq(AiTaskRunEntity::getProjectId, run.getProjectId())
                    .in(AiTaskRunEntity::getStatus, List.of("claimed", "running")));
            if (projectActive >= valueOr(config.getMaxParallelRuns(), 2)) continue;

            LocalDateTime now = LocalDateTime.now();
            run.setRunnerId(runner.getId());
            run.setStatus("running");
            run.setClaimedAt(now);
            run.setStartedAt(now);
            runMapper.updateById(run);
            setTaskStatus(run.getTaskId(), "AI 进行中");
            TaskEntity task = taskMapper.selectById(run.getTaskId());
            ProjectEntity project = projectMapper.selectById(run.getProjectId());
            return new AiRunnerClaimDto(run.getId(), run.getTaskId(), task.getTitle(), run.getProjectId(),
                    project == null ? run.getProjectId() : project.getName(), run.getPromptUrl(),
                    config.getRepoUrl(), blankOr(config.getDefaultBranch(), "main"),
                    blankOr(config.getWorkspaceDir(), defaultFolder(run.getProjectId())), run.getBranchName());
        }
        return null;
    }

    /** 节点回传退出码和摘要；成功进入待验证，失败回到进行中。 */
    public synchronized AiRunDto complete(String token, String runId, CompleteAiRunRequest request) {
        AiRunnerNodeEntity runner = requireRunner(token);
        AiTaskRunEntity run = runMapper.selectById(runId);
        if (run == null) throw new NotFoundException("AI 运行不存在：" + runId);
        if (!runner.getId().equals(run.getRunnerId())) {
            throw new UnauthorizedException("该运行不属于当前执行节点");
        }
        int exitCode = request.exitCode() == null ? -1 : request.exitCode();
        run.setExitCode(exitCode);
        run.setStatus(exitCode == 0 ? "succeeded" : "failed");
        run.setResultSummary(limit(request.resultSummary(), MAX_RESULT));
        run.setSessionId(limit(request.sessionId(), 128));
        run.setFinishedAt(LocalDateTime.now());
        runMapper.updateById(run);
        setTaskStatus(run.getTaskId(), exitCode == 0 ? "待验证" : "进行中");
        runner.setLastSeenAt(LocalDateTime.now());
        runnerMapper.updateById(runner);
        return toRunDtos(List.of(run)).get(0);
    }

    private void recoverStaleRuns() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(3);
        List<AiTaskRunEntity> activeRuns = runMapper.selectList(new LambdaQueryWrapper<AiTaskRunEntity>()
                .in(AiTaskRunEntity::getStatus, List.of("claimed", "running"))
                .isNotNull(AiTaskRunEntity::getRunnerId));
        for (AiTaskRunEntity run : activeRuns) {
            AiRunnerNodeEntity runner = runnerMapper.selectById(run.getRunnerId());
            if (runner == null || runner.getLastSeenAt() == null || runner.getLastSeenAt().isBefore(cutoff)) {
                run.setStatus("queued");
                run.setRunnerId(null);
                run.setClaimedAt(null);
                run.setStartedAt(null);
                run.setResultSummary("执行节点离线，已自动重新排队");
                runMapper.updateById(run);
                setTaskStatus(run.getTaskId(), "进行中");
            }
        }
    }

    private List<AiRunDto> toRunDtos(List<AiTaskRunEntity> runs) {
        if (runs.isEmpty()) return List.of();
        Map<String, TaskEntity> tasks = taskMapper.selectBatchIds(runs.stream().map(AiTaskRunEntity::getTaskId)
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(TaskEntity::getId, Function.identity()));
        Map<String, ProjectEntity> projects = projectMapper.selectBatchIds(runs.stream()
                        .map(AiTaskRunEntity::getProjectId).distinct().toList()).stream()
                .collect(Collectors.toMap(ProjectEntity::getId, Function.identity()));
        List<String> runnerIds = runs.stream().map(AiTaskRunEntity::getRunnerId)
                .filter(StringUtils::hasText).distinct().toList();
        Map<String, AiRunnerNodeEntity> runners = runnerIds.isEmpty() ? Map.of()
                : runnerMapper.selectBatchIds(runnerIds).stream()
                        .collect(Collectors.toMap(AiRunnerNodeEntity::getId, Function.identity()));
        return runs.stream().map(run -> {
            TaskEntity task = tasks.get(run.getTaskId());
            ProjectEntity project = projects.get(run.getProjectId());
            AiRunnerNodeEntity runner = StringUtils.hasText(run.getRunnerId())
                    ? runners.get(run.getRunnerId()) : null;
            return new AiRunDto(run.getId(), run.getTaskId(), task == null ? run.getTaskId() : task.getTitle(),
                    run.getProjectId(), project == null ? run.getProjectId() : project.getName(), run.getStatus(),
                    run.getRunnerId(), runner == null ? null : runner.getName(), run.getBranchName(),
                    run.getResultSummary(), run.getSessionId(), run.getExitCode(), text(run.getCreatedAt()),
                    text(run.getStartedAt()), text(run.getFinishedAt()));
        }).toList();
    }

    private AiRunnerNodeEntity requireRunner(String token) {
        if (!StringUtils.hasText(token)) throw new UnauthorizedException("缺少执行节点令牌");
        AiRunnerNodeEntity runner = runnerMapper.selectOne(new LambdaQueryWrapper<AiRunnerNodeEntity>()
                .eq(AiRunnerNodeEntity::getTokenHash, hash(token.trim())));
        if (runner == null) throw new UnauthorizedException("执行节点令牌无效");
        return runner;
    }

    private ProjectEntity requireProject(String projectId) {
        ProjectEntity project = projectMapper.selectById(projectId);
        if (project == null) throw new NotFoundException("项目不存在：" + projectId);
        return project;
    }

    private void setTaskStatus(String taskId, String status) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null) return;
        task.setStatus(status);
        taskMapper.updateById(task);
    }

    private long countRuns(LambdaQueryWrapper<AiTaskRunEntity> query) {
        Long count = runMapper.selectCount(query);
        return count == null ? 0 : count;
    }

    private AiProjectConfigDto toConfigDto(AiProjectConfigEntity entity) {
        return new AiProjectConfigDto(entity.getProjectId(), Boolean.TRUE.equals(entity.getEnabled()),
                blankOr(entity.getRepoUrl(), ""), blankOr(entity.getDefaultBranch(), "main"),
                blankOr(entity.getWorkspaceDir(), defaultFolder(entity.getProjectId())),
                entity.getPreferredRunnerId(), valueOr(entity.getMaxParallelRuns(), 2));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static String randomText(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) out.append(PAIR_ALPHABET.charAt(RANDOM.nextInt(PAIR_ALPHABET.length())));
        return out.toString();
    }

    private static String id(String prefix) {
        return prefix + Long.toString(System.currentTimeMillis(), 36)
                + Integer.toString(RANDOM.nextInt(1_679_616), 36);
    }

    private static String safeFolder(String name, String fallback) {
        String safe = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("-+", "-");
        return StringUtils.hasText(safe) ? safe : defaultFolder(fallback);
    }

    private static String defaultFolder(String projectId) {
        return "wao-project-" + projectId;
    }

    private static int valueOr(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static int clamp(Integer value, int min, int max, int fallback) {
        return value == null ? fallback : Math.max(min, Math.min(max, value));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankOr(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static String limit(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String text(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}
