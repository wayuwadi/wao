package cn.wayuwa.wao.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.AiProjectConfigEntity;
import cn.wayuwa.wao.domain.AiRunProgressEntity;
import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import cn.wayuwa.wao.domain.AiRunnerPairCodeEntity;
import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.AiPairCodeDto;
import cn.wayuwa.wao.dto.AiRunProgressDto;
import cn.wayuwa.wao.dto.AiRunUsageDto;
import cn.wayuwa.wao.dto.AiProjectConfigDto;
import cn.wayuwa.wao.dto.AiRunDto;
import cn.wayuwa.wao.dto.AiRunnerClaimDto;
import cn.wayuwa.wao.dto.AiRunnerDto;
import cn.wayuwa.wao.dto.AiRunnerLogDto;
import cn.wayuwa.wao.dto.AiWorkflowDto;
import cn.wayuwa.wao.dto.CompleteAiRunRequest;
import cn.wayuwa.wao.dto.CreateTaskRequest;
import cn.wayuwa.wao.dto.HostedPageDto;
import cn.wayuwa.wao.dto.PairedAiRunnerDto;
import cn.wayuwa.wao.dto.PairAiRunnerRequest;
import cn.wayuwa.wao.dto.ReportAiRunProgressRequest;
import cn.wayuwa.wao.dto.RunnerScriptDto;
import cn.wayuwa.wao.dto.SaveAiProjectConfigRequest;
import cn.wayuwa.wao.dto.Step;
import cn.wayuwa.wao.mapper.AiProjectConfigMapper;
import cn.wayuwa.wao.mapper.AiRunProgressMapper;
import cn.wayuwa.wao.mapper.AiRunnerNodeMapper;
import cn.wayuwa.wao.mapper.AiRunnerPairCodeMapper;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * AI 任务执行中心。
 *
 * <p>WAO 只保存队列、节点心跳和运行结果；Codex / Claude Code 登录态、Git 凭据与实际工作目录
 * 始终留在用户电脑上的 runner 进程中。领取动作串行化，避免两台电脑拿到同一条任务。
 */
@Service
public class AiRunService {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AiRunService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PAIR_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final List<String> ACTIVE = List.of("queued", "claimed", "running");
    /** 已经有结论、因而可以在原会话上继续对话的状态。 */
    private static final List<String> FINISHED = List.of("succeeded", "failed");
    private static final int MAX_RESULT = 32_000;
    private static final int MAX_RUNNER_LOG = 30_000;
    private static final int MAX_PROGRESS = 1_000;
    private static final int MAX_PROGRESS_PER_RUN = 50;
    private static final Set<String> PROGRESS_KINDS = Set.of("phase", "message", "plan", "result");
    /** 目前支持的本地 AI 工具；顺序即页面默认展示顺序。 */
    public static final List<String> ENGINES = List.of("codex", "claude", "qoder");
    private static final String DEFAULT_ENGINE = "codex";
    private static final Map<String, String> ENGINE_LABELS = Map.of("codex", "Codex", "claude", "Claude Code",
            "qoder", "Qoder");

    private final AiProjectConfigMapper configMapper;
    private final AiRunnerNodeMapper runnerMapper;
    private final AiRunnerPairCodeMapper pairMapper;
    private final AiTaskRunMapper runMapper;
    private final AiRunProgressMapper progressMapper;
    private final TaskMapper taskMapper;
    private final ProjectMapper projectMapper;
    private final TaskService tasks;
    private final FeishuTaskNotifier notifier;
    private final RunnerScriptService scripts;
    private final AiAccountService accounts;
    private final HostedPageService hostedPages;

    public AiRunService(AiProjectConfigMapper configMapper, AiRunnerNodeMapper runnerMapper,
            AiRunnerPairCodeMapper pairMapper, AiTaskRunMapper runMapper, AiRunProgressMapper progressMapper,
            TaskMapper taskMapper, ProjectMapper projectMapper, TaskService tasks, FeishuTaskNotifier notifier,
            RunnerScriptService scripts, AiAccountService accounts, HostedPageService hostedPages) {
        this.configMapper = configMapper;
        this.runnerMapper = runnerMapper;
        this.pairMapper = pairMapper;
        this.runMapper = runMapper;
        this.progressMapper = progressMapper;
        this.taskMapper = taskMapper;
        this.projectMapper = projectMapper;
        this.tasks = tasks;
        this.notifier = notifier;
        this.scripts = scripts;
        this.accounts = accounts;
        this.hostedPages = hostedPages;
    }

    /** 读取项目配置；尚未保存过时返回安全的关闭状态。 */
    public AiProjectConfigDto getConfig(String projectId) {
        requireProject(projectId);
        AiProjectConfigEntity entity = configMapper.selectById(projectId);
        return entity == null
                ? new AiProjectConfigDto(projectId, false, "", "main", defaultFolder(projectId), DEFAULT_ENGINE, null, 2)
                : toConfigDto(entity);
    }

    /**
     * AI 控制台的工作流总览：每个项目一行配置与运行统计，未配置过的项目也会出现。
     *
     * <p>历史上项目删除不会级联清理 AI 配置。孤儿配置也必须返回，否则用户看不到、更无法主动清理；
     * 这类条目标记为项目已删除，并排在正常项目之后。
     */
    public List<AiWorkflowDto> listWorkflows() {
        Map<String, AiProjectConfigEntity> configs = configMapper.selectList(null).stream()
                .collect(Collectors.toMap(AiProjectConfigEntity::getProjectId, Function.identity()));
        Map<String, String> runnerNames = runnerMapper.selectList(null).stream()
                .collect(Collectors.toMap(AiRunnerNodeEntity::getId, AiRunnerNodeEntity::getName));
        // 总览只需要状态和时间，不读 result_summary 这类大字段。
        Map<String, List<AiTaskRunEntity>> runsByProject = runMapper.selectList(
                        new QueryWrapper<AiTaskRunEntity>().select("project_id", "status", "created_at"))
                .stream().collect(Collectors.groupingBy(AiTaskRunEntity::getProjectId));
        List<ProjectEntity> projects = projectMapper.selectList(null);
        Set<String> projectIds = projects.stream().map(ProjectEntity::getId).collect(Collectors.toSet());
        List<AiWorkflowDto> workflows = new ArrayList<>();
        projects.forEach(project -> {
            AiProjectConfigEntity config = configs.get(project.getId());
            AiProjectConfigDto dto = config == null
                    ? new AiProjectConfigDto(project.getId(), false, "", "main",
                            safeFolder(project.getName(), project.getId()), DEFAULT_ENGINE, null, 2)
                    : toConfigDto(config);
            workflows.add(toWorkflow(project.getId(), project.getName(), dto, config != null, false,
                    runnerNames, runsByProject));
        });
        configs.values().stream()
                .filter(config -> !projectIds.contains(config.getProjectId()))
                .forEach(config -> workflows.add(toWorkflow(config.getProjectId(),
                        "已删除项目 · " + config.getProjectId(), toConfigDto(config), true, true,
                        runnerNames, runsByProject)));
        workflows.sort((left, right) -> {
            int enabled = Boolean.compare(right.config().enabled(), left.config().enabled());
            if (enabled != 0) return enabled;
            int deleted = Boolean.compare(left.projectDeleted(), right.projectDeleted());
            return deleted != 0 ? deleted : left.projectName().compareToIgnoreCase(right.projectName());
        });
        return workflows;
    }

    /** 删除一条已保存的 AI 工作流配置；历史运行记录保留用于追溯。 */
    @Transactional
    public void deleteWorkflow(String projectId) {
        if (configMapper.selectById(projectId) == null) {
            throw new NotFoundException("AI 工作流不存在或已经删除");
        }
        Long count = runMapper.selectCount(new QueryWrapper<AiTaskRunEntity>()
                .eq("project_id", projectId).in("status", ACTIVE));
        long activeRuns = count == null ? 0 : count;
        if (activeRuns > 0) {
            throw new BadRequestException("这条工作流还有 " + activeRuns + " 个运行正在排队或执行，请先取消或等它跑完");
        }
        configMapper.deleteById(projectId);
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
        String engine = requireEngine(blankOr(request.defaultEngine(), DEFAULT_ENGINE));
        String preferred = trim(request.preferredRunnerId());
        if (StringUtils.hasText(preferred)) {
            AiRunnerNodeEntity runner = runnerMapper.selectById(preferred);
            if (runner == null) throw new BadRequestException("指定的执行电脑不存在");
            if (!engines(runner).contains(engine)) {
                throw new BadRequestException(runner.getName() + " 没有接入 " + label(engine) + "，请换一台电脑或改默认 AI 工具");
            }
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
        entity.setDefaultEngine(engine);
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
        runner.setEngines(joinEngines(request.engines()));
        runner.setMaxConcurrency(clamp(request.maxConcurrency(), 1, 8, 1));
        runner.setTokenHash(hash(token));
        runner.setCreatedAt(now);
        runner.setLastSeenAt(now);
        runner.setOnlineState(true);
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
        // 按配对先后排序（id 兜底），避免各节点心跳交替刷新 lastSeenAt 导致卡片顺序来回跳。
        return runnerMapper.selectList(new LambdaQueryWrapper<AiRunnerNodeEntity>()
                        .orderByAsc(AiRunnerNodeEntity::getCreatedAt)
                        .orderByAsc(AiRunnerNodeEntity::getId))
                .stream().map(r -> new AiRunnerDto(r.getId(), r.getName(), r.getPlatform(), engines(r),
                        effectiveMaxConcurrency(r), r.getAdminMaxConcurrency(),
                        activeByRunner.getOrDefault(r.getId(), 0L).intValue(),
                        r.getLastSeenAt() != null && r.getLastSeenAt().isAfter(cutoff),
                        r.getLastSeenAt() == null ? null : r.getLastSeenAt().toString(),
                        r.getWakeMac(), r.getWakeHost(), r.getWakePort() == null ? 9 : r.getWakePort(),
                        StringUtils.hasText(r.getWakeMac()) && StringUtils.hasText(r.getWakeHost()),
                        r.getLastWakeRequestedAt() == null ? null : r.getLastWakeRequestedAt().toString(),
                        scriptUpToDate(r), r.getUpdateRequestedAt() != null))
                .toList();
    }

    /** 返回一台执行电脑最近上报的已脱敏日志快照；尚未上报时内容为空。 */
    public AiRunnerLogDto runnerLog(String runnerId) {
        AiRunnerNodeEntity runner = runnerMapper.selectById(runnerId);
        if (runner == null) throw new NotFoundException("执行电脑不存在");
        return new AiRunnerLogDto(runner.getId(), runner.getName(), blankOr(runner.getLogTail(), ""),
                runner.getLogUpdatedAt() == null ? null : runner.getLogUpdatedAt().toString());
    }

    /** 脚本是否最新；节点没上报过哈希或服务端没打包脚本时无法判断，返回 null。 */
    private Boolean scriptUpToDate(AiRunnerNodeEntity runner) {
        if (!scripts.available() || !StringUtils.hasText(runner.getScriptHash())) return null;
        return scripts.hash().equals(runner.getScriptHash());
    }

    /** 一键把最新 Runner 脚本下发给所有执行电脑；已是最新的机器自动跳过。 */
    public List<AiRunnerDto> requestScriptUpdate() {
        if (!scripts.available()) {
            throw new BadRequestException("服务端构建里没有打包 Runner 脚本，无法下发更新");
        }
        LocalDateTime now = LocalDateTime.now();
        for (AiRunnerNodeEntity runner : runnerMapper.selectList(null)) {
            if (scripts.hash().equals(runner.getScriptHash())) continue;
            runner.setUpdateRequestedAt(now);
            runnerMapper.updateById(runner);
        }
        return listRunners();
    }

    /** 执行电脑收到更新指令后来拉取最新脚本。 */
    public RunnerScriptDto fetchRunnerScript(String token) {
        requireRunner(token);
        if (!scripts.available()) throw new NotFoundException("服务端构建里没有打包 Runner 脚本");
        return new RunnerScriptDto(scripts.hash(), scripts.content());
    }

    /** 管理员在后台修改某台执行电脑的最大并发线程数。 */
    public AiRunnerDto updateRunnerMaxConcurrency(String runnerId, int maxConcurrency) {
        AiRunnerNodeEntity runner = runnerMapper.selectById(runnerId);
        if (runner == null) throw new NotFoundException("执行电脑不存在：" + runnerId);
        runner.setAdminMaxConcurrency(clamp(maxConcurrency, 1, 8, 1));
        runnerMapper.updateById(runner);
        return listRunners().stream().filter(r -> r.id().equals(runnerId)).findFirst().orElseThrow();
    }

    /**
     * 解除一台执行电脑的接入：删除节点记录，同时作废它保存在本机的令牌。
     *
     * <p>还有任务在这台电脑上跑时拒绝解除——直接删记录会让这些运行永远收不到回执，
     * 只能等超时回收，用户看到的是任务莫名卡住。排队中点名这台电脑的运行会改回自动
     * 分配，项目里把它设成首选电脑的配置也一并清空，否则这些运行永远等不到人领。
     *
     * @param runnerId 执行电脑 id
     * @return 被解除的那台电脑解除前的展示信息，供调用方发通知
     * @throws NotFoundException   电脑不存在（例如两个页面同时点了解除）
     * @throws BadRequestException 这台电脑还有 AI 任务在执行
     */
    @Transactional
    public synchronized AiRunnerDto removeRunner(String runnerId) {
        AiRunnerNodeEntity runner = runnerMapper.selectById(runnerId);
        if (runner == null) throw new NotFoundException("执行电脑不存在：" + runnerId);
        AiRunnerDto removed = listRunners().stream()
                .filter(r -> r.id().equals(runnerId)).findFirst().orElseThrow();
        long active = countRuns(new LambdaQueryWrapper<AiTaskRunEntity>()
                .eq(AiTaskRunEntity::getRunnerId, runnerId)
                .in(AiTaskRunEntity::getStatus, List.of("claimed", "running")));
        if (active > 0) {
            throw new BadRequestException(runner.getName() + " 上还有 " + active
                    + " 条 AI 任务在执行，请先取消这些运行或等它跑完，再解除接入");
        }
        int released = runMapper.update(null, new UpdateWrapper<AiTaskRunEntity>()
                .eq("requested_runner_id", runnerId)
                .eq("status", "queued")
                .set("requested_runner_id", null));
        int unpinned = configMapper.update(null, new UpdateWrapper<AiProjectConfigEntity>()
                .eq("preferred_runner_id", runnerId)
                .set("preferred_runner_id", null));
        runnerMapper.deleteById(runnerId);
        LOG.info("Removed AI runner {} ({}); released {} queued runs and {} project preferences",
                runnerId, runner.getName(), released, unpinned);
        return removed;
    }

    /**
     * 把一个或多个任务加入队列；同一任务（或同一条子任务）已有活动运行时不会重复入队。
     *
     * @param subId    只跑主任务下的某一条子任务时传它的标识；留空表示跑整个任务
     * @param engine   本次使用的 AI 工具；留空表示沿用各任务所属项目的默认工具
     * @param runnerId 指定执行电脑；留空表示由任意在线且支持该工具的电脑领取
     * @param accountId 指定本次运行的集中账号；留空表示使用 Runner 本地登录态
     */
    @Transactional
    public synchronized List<AiRunDto> enqueue(List<String> taskIds, String subId, String engine, String runnerId,
            String accountId, String requestedBy, String baseUrl) {
        String requestedEngine = StringUtils.hasText(engine) && !"auto".equals(engine)
                ? requireEngine(engine) : null;
        String sub = trim(subId);
        if (StringUtils.hasText(sub) && taskIds.stream().distinct().count() != 1) {
            throw new BadRequestException("按子任务派工时一次只能选一个任务");
        }
        AiRunnerNodeEntity target = null;
        if (StringUtils.hasText(runnerId)) {
            target = runnerMapper.selectById(runnerId.trim());
            if (target == null) throw new BadRequestException("指定的执行电脑不存在");
        }
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
            String runEngine = requestedEngine != null
                    ? requestedEngine : requireEngine(blankOr(config.getDefaultEngine(), DEFAULT_ENGINE));
            if (StringUtils.hasText(accountId)) accounts.validateRunAccount(accountId.trim(), runEngine);
            AiRunnerNodeEntity runTarget = target;
            if (runTarget == null && StringUtils.hasText(accountId)) {
                runTarget = preferredLinuxRunner(runEngine);
                if (runTarget == null) {
                    throw new BadRequestException("Linux 主节点尚未接入 " + label(runEngine)
                            + "，请先安装对应 CLI 并重启 Runner，或明确指定其他执行电脑");
                }
            }
            if (runTarget != null && !engines(runTarget).contains(runEngine)) {
                throw new BadRequestException(runTarget.getName() + " 没有接入 " + label(runEngine) + "，请换一台电脑或换一个 AI 工具");
            }
            if (runTarget == null && !anyRunnerSupports(runEngine)) {
                throw new BadRequestException("还没有电脑接入 " + label(runEngine) + "，请先在 AI 控制台配对一台");
            }
            String subTitle = StringUtils.hasText(sub) ? requireSubTitle(taskId, sub) : null;
            LambdaQueryWrapper<AiTaskRunEntity> duplicateQuery = new LambdaQueryWrapper<AiTaskRunEntity>()
                    .eq(AiTaskRunEntity::getTaskId, taskId).in(AiTaskRunEntity::getStatus, ACTIVE);
            // 主任务和它的子任务是两条互不相干的派工，各自去重即可。
            if (StringUtils.hasText(sub)) duplicateQuery.eq(AiTaskRunEntity::getSubId, sub);
            else duplicateQuery.isNull(AiTaskRunEntity::getSubId);
            Long duplicate = runMapper.selectCount(duplicateQuery);
            if (duplicate != null && duplicate > 0) continue;
            AiTaskRunEntity run = new AiTaskRunEntity();
            run.setId(id("ar"));
            run.setTaskId(taskId);
            run.setSubId(StringUtils.hasText(sub) ? sub : null);
            run.setProjectId(task.getProjectId());
            run.setEngine(runEngine);
            run.setAiAccountId(StringUtils.hasText(accountId) ? accountId.trim() : null);
            run.setStatus("queued");
            run.setRequestedRunnerId(runTarget == null ? null : runTarget.getId());
            run.setRequestedBy(requestedBy);
            run.setPromptUrl(promptUrl(baseUrl, taskId, sub));
            run.setBranchName("ai/wao-" + run.getId());
            run.setCreatedAt(LocalDateTime.now());
            runMapper.insert(run);
            appendProgress(run, "phase", (subTitle == null ? "任务" : "子任务「" + subTitle + "」")
                    + "已进入队列，等待" + (runTarget == null ? "在线电脑" : runTarget.getName())
                    + "上的 " + label(runEngine) + " 领取");
            setTaskStatus(taskId, "AI 进行中");
            notifyQueued(run);
            created.add(run);
        }
        return toRunDtos(created);
    }

    /**
     * 把用户只描述了结果的目标转成任务，并立即派给项目的默认 AI 工作流。
     *
     * <p>创建任务与入队在同一事务中；配置或执行节点不可用时不会留下一条无人处理的半成品任务。
     * AI 收到的描述保留原始目标，另外附上自主执行约定，让它先盘点仓库和工具再自行拆解推进。
     *
     * @param projectId 承载目标的项目，必须已启用 AI Run
     * @param goal 用户输入的原始目标
     * @param requestedBy 发起人
     * @param baseUrl 用于生成 Runner 可读的任务分享地址
     * @return 新建并已入队的运行
     */
    @Transactional
    public synchronized AiRunDto launchGoal(String projectId, String goal, String requestedBy, String baseUrl) {
        return launchGoal(projectId, goal, requestedBy, baseUrl, null);
    }

    /**
     * 使用指定集中账号创建自主目标；额度驱动的自动进化用它保证实际消耗的就是通过门槛的账号。
     *
     * @param projectId 承载目标的项目
     * @param goal 用户或系统选择的目标
     * @param requestedBy 发起人
     * @param baseUrl 公网任务分享根地址
     * @param aiAccountId 本次运行固定使用的集中账号；人工自由目标可为空
     * @return 新建并已入队的运行
     */
    @Transactional
    public synchronized AiRunDto launchGoal(String projectId, String goal, String requestedBy, String baseUrl,
            String aiAccountId) {
        String normalizedGoal = trim(goal);
        if (!StringUtils.hasText(normalizedGoal)) throw new BadRequestException("请先写下想让 AI 完成的目标");

        cn.wayuwa.wao.dto.TaskDto task = tasks.create(new CreateTaskRequest(
                goalTitle(normalizedGoal), projectId, "P2", requestedBy, "",
                autonomousGoalDescription(normalizedGoal), List.of(), null, null, null), requestedBy);
        List<AiRunDto> runs = enqueue(List.of(task.id()), null, null, null, aiAccountId, requestedBy, baseUrl);
        if (runs.isEmpty()) throw new IllegalStateException("自主目标创建后未生成 AI 运行");
        return runs.get(0);
    }

    private static String goalTitle(String goal) {
        String firstLine = goal.lines().map(String::trim).filter(StringUtils::hasText).findFirst().orElse("新的自主目标");
        firstLine = firstLine.replaceFirst("^#{1,6}\\s*", "").trim();
        return firstLine.length() <= 80 ? firstLine : firstLine.substring(0, 79) + "…";
    }

    private static String autonomousGoalDescription(String goal) {
        return "## 目标\n\n" + goal + "\n\n"
                + "## 自主执行约定\n\n"
                + "- 先理解想要达成的结果，主动盘点当前仓库、文档、工具与可用服务。\n"
                + "- 自己拆解步骤、排定优先级并持续推进；能安全完成的事直接做完并验证。\n"
                + "- 不伪造数据或能力；缺少非必要资源时先采用可行替代方案。\n"
                + "- 只在缺少关键信息、需要新权限或即将执行不可逆操作时暂停并明确说明阻塞。\n"
                + "- 最终汇报已完成的结果、验证证据、未完成项与下一步建议。";
    }

    /** 集中账号默认固定到支持对应 CLI 的 Linux 主节点；在线节点优先，离线时保留排队语义。 */
    private AiRunnerNodeEntity preferredLinuxRunner(String engine) {
        List<AiRunnerNodeEntity> linux = runnerMapper.selectList(new LambdaQueryWrapper<AiRunnerNodeEntity>()).stream()
                .filter(runner -> isLinuxRunner(runner) && engines(runner).contains(engine))
                .toList();
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(2);
        return linux.stream().filter(runner -> runner.getLastSeenAt() != null
                        && runner.getLastSeenAt().isAfter(cutoff) && isMainRunner(runner))
                .findFirst()
                .or(() -> linux.stream().filter(runner -> runner.getLastSeenAt() != null
                        && runner.getLastSeenAt().isAfter(cutoff)).findFirst())
                .or(() -> linux.stream().filter(AiRunService::isMainRunner).findFirst())
                .orElse(linux.isEmpty() ? null : linux.get(0));
    }

    private static boolean isLinuxRunner(AiRunnerNodeEntity runner) {
        return lower(runner.getPlatform()).contains("linux") || lower(runner.getName()).contains("linux");
    }

    private static boolean isMainRunner(AiRunnerNodeEntity runner) {
        return StringUtils.hasText(runner.getName()) && runner.getName().contains("主节点");
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 这次运行要交给 AI 读的 Markdown 地址。
     *
     * <p>主任务一律带 {@code openOnly=true}：已完成、以及已经单独派过工的子任务不该再进入
     * 本次范围，否则同一件事会被两台电脑各做一遍（见需求「避免浪费算力」）。
     */
    private static String promptUrl(String baseUrl, String taskId, String subId) {
        return StringUtils.hasText(subId)
                ? baseUrl + "/api/share/tasks/" + taskId + "/subs/" + subId + ".md"
                : baseUrl + "/api/share/tasks/" + taskId + ".md?openOnly=true";
    }

    /** 校验子任务确实存在，并取出标题用于进展文案。 */
    private String requireSubTitle(String taskId, String subId) {
        String title = subTitle(taskId, subId);
        if (title == null) throw new NotFoundException("子任务不存在：" + subId);
        return title;
    }

    /** 子任务标题；任务或子任务已经被删掉时返回 null。 */
    private String subTitle(String taskId, String subId) {
        List<Step> subs;
        try {
            subs = tasks.get(taskId).subs();
        } catch (NotFoundException ex) {
            return null;
        }
        for (int i = 0; i < subs.size(); i++) {
            Step step = subs.get(i);
            String key = StringUtils.hasText(step.id()) ? step.id() : taskId + "#" + i;
            if (key.equals(subId)) return step.text();
        }
        return null;
    }

    /**
     * 这个任务下已经单独派过 AI 工的子任务标识。
     *
     * <p>分享链接据此把它们从清单里摘掉 —— 已经有人（有电脑）在做的条目，不该再被下一个
     * AI 当成待办重做一遍。
     *
     * @param taskId 任务 id
     * @return 子任务标识集合；一条都没派过时为空集
     */
    public Set<String> dispatchedSubIds(String taskId) {
        return runMapper.selectList(new LambdaQueryWrapper<AiTaskRunEntity>()
                        .eq(AiTaskRunEntity::getTaskId, taskId)
                        .isNotNull(AiTaskRunEntity::getSubId))
                .stream().map(AiTaskRunEntity::getSubId).filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }

    /**
     * 基于一次失败运行重新排队，让 AI 拿着上次的报错接着修。
     *
     * <p>部署失败、CI 挂掉这类问题，绝大多数不需要从零重来一遍：把失败摘要原样交回给 AI，
     * 它可以直接定位到出错的地方。重跑是一条新的运行记录（新分支、新执行档案），
     * 通过 {@code retryOfRunId} 指回原来那次，页面上能串成一条修复链。
     *
     * @param runId       要重跑的失败运行 id
     * @param requestedBy 触发重跑的成员 id
     * @param baseUrl     当前站点地址，用于拼任务分享链接
     * @return 新建的运行记录
     * @throws BadRequestException 该运行还没结束、或已经在重跑中时
     */
    @Transactional
    public synchronized AiRunDto rerun(String runId, String requestedBy, String baseUrl) {
        AiTaskRunEntity source = runMapper.selectById(runId);
        if (source == null) throw new NotFoundException("AI 运行不存在：" + runId);
        if (ACTIVE.contains(source.getStatus())) {
            throw new BadRequestException("这次运行还没结束，等它跑完再决定要不要重跑");
        }
        LambdaQueryWrapper<AiTaskRunEntity> activeQuery = new LambdaQueryWrapper<AiTaskRunEntity>()
                .eq(AiTaskRunEntity::getTaskId, source.getTaskId()).in(AiTaskRunEntity::getStatus, ACTIVE);
        if (StringUtils.hasText(source.getSubId())) activeQuery.eq(AiTaskRunEntity::getSubId, source.getSubId());
        else activeQuery.isNull(AiTaskRunEntity::getSubId);
        Long active = runMapper.selectCount(activeQuery);
        if (active != null && active > 0) {
            throw new BadRequestException("这个任务已经有一次运行在进行中，不用重复重跑");
        }
        AiProjectConfigEntity config = configMapper.selectById(source.getProjectId());
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
            throw new BadRequestException("项目尚未开启 AI Run，请先在项目设置中配置");
        }
        String engine = requireEngine(blankOr(source.getEngine(), DEFAULT_ENGINE));
        if (!anyRunnerSupports(engine)) {
            throw new BadRequestException("还没有电脑接入 " + label(engine) + "，请先在 AI 控制台配对一台");
        }
        AiTaskRunEntity run = new AiTaskRunEntity();
        run.setId(id("ar"));
        run.setTaskId(source.getTaskId());
        run.setSubId(source.getSubId());
        run.setProjectId(source.getProjectId());
        run.setEngine(engine);
        run.setAiAccountId(source.getAiAccountId());
        run.setStatus("queued");
        run.setRequestedRunnerId(source.getRequestedRunnerId());
        run.setRequestedBy(requestedBy);
        run.setPromptUrl(blankOr(source.getPromptUrl(),
                promptUrl(baseUrl, source.getTaskId(), source.getSubId())));
        run.setBranchName("ai/wao-" + run.getId());
        run.setRetryOfRunId(source.getId());
        run.setCreatedAt(LocalDateTime.now());
        runMapper.insert(run);
        appendProgress(run, "phase", "已带着上一次的报错重新排队，等待 " + label(engine) + " 接着修");
        setTaskStatus(run.getTaskId(), "AI 进行中");
        notifyQueued(run);
        return toRunDtos(List.of(run)).get(0);
    }

    /**
     * 带着一段新的提示词让 AI 在原会话里接着跑。
     *
     * <p>失败和成功都能续：跑完不符合预期时，用户要的是「基于这次的记忆再优化一版」，
     * 而不是新建一条任务把同样的话再说一遍，也不是把已经做对的部分推倒重来。
     * 会话与 worktree 都留在原来那台电脑上，所以这次续跑固定派回它。
     *
     * @param runId       要继续的运行 id
     * @param input       用户补充的提示词 / 优化建议
     * @param requestedBy 触发续跑的成员 id
     * @param baseUrl     当前站点地址，用于把输入里粘贴的站内截图补成绝对地址
     * @return 更新后的运行记录
     * @throws BadRequestException 运行还在进行中，或这次运行没有可恢复的会话时
     */
    @Transactional
    public synchronized AiRunDto continueRun(String runId, String input, String requestedBy, String baseUrl) {
        AiTaskRunEntity run = runMapper.selectById(runId);
        if (run == null) throw new NotFoundException("AI 运行不存在：" + runId);
        if (ACTIVE.contains(run.getStatus())) throw new BadRequestException("AI 正在运行，暂时不能重复输入");
        if (!FINISHED.contains(run.getStatus()) || !StringUtils.hasText(run.getSessionId())
                || !StringUtils.hasText(run.getRunnerId())) {
            throw new BadRequestException("这次运行无法恢复会话，请使用基于报错重跑");
        }
        // 会话和 worktree 都在原来那台电脑上；它已经被解除接入，这次续跑没人能领。
        if (runnerMapper.selectById(run.getRunnerId()) == null) {
            throw new BadRequestException("原来那台执行电脑已解除接入，会话无法恢复，请使用基于报错重跑");
        }
        boolean optimizing = "succeeded".equals(run.getStatus());
        run.setPendingInput(limit(ShareService.absolutizeMarkdown(input, baseUrl), 4000));
        run.setRequestedBy(requestedBy);
        run.setRequestedRunnerId(run.getRunnerId());
        run.setStatus("queued");
        run.setExitCode(null);
        run.setFinishedAt(null);
        appendProgress(run, "message", (optimizing ? "用户要求继续优化：" : "用户补充：") + run.getPendingInput());
        appendProgress(run, "phase", "已收到" + (optimizing ? "优化要求" : "补充") + "，等待原执行电脑恢复 AI 会话");
        setTaskStatus(run.getTaskId(), "AI 进行中");
        notifyQueued(run);
        return toRunDtos(List.of(run)).get(0);
    }

    /** 重跑时要交给 AI 的上一次失败摘要；不是重跑或找不到原运行时返回 null。 */
    private String previousFailure(AiTaskRunEntity run) {
        if (!StringUtils.hasText(run.getRetryOfRunId())) return null;
        AiTaskRunEntity source = runMapper.selectById(run.getRetryOfRunId());
        return source == null ? null : source.getResultSummary();
    }

    /** 最近运行；activeOnly 为 true 时只返回排队和运行中的记录。 */
    public List<AiRunDto> listRuns(boolean activeOnly) {
        recoverStaleRuns();
        LambdaQueryWrapper<AiTaskRunEntity> query = new LambdaQueryWrapper<AiTaskRunEntity>()
                .orderByDesc(AiTaskRunEntity::getCreatedAt).last("LIMIT 100");
        if (activeOnly) query.in(AiTaskRunEntity::getStatus, ACTIVE);
        return toRunDtos(runMapper.selectList(query));
    }

    /** 返回指定任务的 AI 执行档案，供任务详情长期回看。 */
    public List<AiRunDto> listTaskRuns(String taskId) {
        if (taskMapper.selectById(taskId) == null) throw new NotFoundException("任务不存在：" + taskId);
        return toRunDtos(runMapper.selectList(new LambdaQueryWrapper<AiTaskRunEntity>()
                .eq(AiTaskRunEntity::getTaskId, taskId)
                .orderByDesc(AiTaskRunEntity::getCreatedAt)
                .last("LIMIT 30")));
    }

    /** 节点心跳；刷新本机能力，并在响应中返回后台优先的实际并发数供 Runner 动态生效。 */
    public AiRunnerDto heartbeat(String token, Integer maxConcurrency, String engines, String scriptHash,
            String wakeMac, String wakeHost, String logTail) {
        AiRunnerNodeEntity runner = requireRunner(token);
        boolean clearUpdateRequest = false;
        boolean storeLog = false;
        LocalDateTime now = LocalDateTime.now();
        runner.setLastSeenAt(now);
        if (maxConcurrency != null) runner.setMaxConcurrency(clamp(maxConcurrency, 1, 8, 1));
        if (StringUtils.hasText(engines)) runner.setEngines(joinEngines(List.of(engines.split(","))));
        if (StringUtils.hasText(scriptHash)) {
            runner.setScriptHash(scriptHash.trim());
            // 节点已经跑在最新脚本上，更新指令自动完成。
            if (scripts.available() && scripts.hash().equals(runner.getScriptHash())) {
                runner.setUpdateRequestedAt(null);
                clearUpdateRequest = true;
            }
        }
        if (StringUtils.hasText(wakeMac)) {
            try {
                runner.setWakeMac(AiRunnerWakeService.normalizeMac(wakeMac));
            } catch (BadRequestException ignored) {
                // 硬件探测失败不能影响正常心跳；Runner 下次上报有效地址时会自动修复。
            }
        }
        if (StringUtils.hasText(wakeHost)) runner.setWakeHost(wakeHost);
        if (StringUtils.hasText(logTail)) {
            String normalized = logTail.strip();
            runner.setLogTail(normalized.length() <= MAX_RUNNER_LOG
                    ? normalized : normalized.substring(normalized.length() - MAX_RUNNER_LOG));
            runner.setLogUpdatedAt(now);
            storeLog = true;
        }
        if (runner.getWakePort() == null) runner.setWakePort(9);
        if (clearUpdateRequest || storeLog) {
            UpdateWrapper<AiRunnerNodeEntity> update = new UpdateWrapper<AiRunnerNodeEntity>()
                    .eq("id", runner.getId());
            // MyBatis-Plus 的 updateById 默认跳过 null；显式 SET NULL，否则节点会永远收到更新指令并重启。
            if (clearUpdateRequest) update.set("update_requested_at", null);
            // 大字段不参与普通心跳的 updateById；只有 Runner 真正上报日志时才显式更新。
            if (storeLog) update.set("log_tail", runner.getLogTail()).set("log_updated_at", now);
            runnerMapper.update(runner, update);
        } else {
            runnerMapper.updateById(runner);
        }
        return listRunners().stream().filter(r -> r.id().equals(runner.getId())).findFirst().orElseThrow();
    }

    /** 节点领取下一条满足节点偏好和项目并发上限的任务。 */
    @Transactional
    public synchronized AiRunnerClaimDto claim(String token) {
        recoverStaleRuns();
        AiRunnerNodeEntity runner = requireRunner(token);
        runner.setLastSeenAt(LocalDateTime.now());
        runnerMapper.updateById(runner);
        long ownActive = countRuns(new LambdaQueryWrapper<AiTaskRunEntity>()
                .eq(AiTaskRunEntity::getRunnerId, runner.getId())
                .in(AiTaskRunEntity::getStatus, List.of("claimed", "running")));
        if (ownActive >= effectiveMaxConcurrency(runner)) return null;

        List<AiTaskRunEntity> queued = runMapper.selectList(new LambdaQueryWrapper<AiTaskRunEntity>()
                .eq(AiTaskRunEntity::getStatus, "queued").orderByAsc(AiTaskRunEntity::getCreatedAt).last("LIMIT 50"));
        List<String> ownEngines = engines(runner);
        for (AiTaskRunEntity run : queued) {
            AiProjectConfigEntity config = configMapper.selectById(run.getProjectId());
            if (config == null || !Boolean.TRUE.equals(config.getEnabled())) continue;
            String engine = blankOr(run.getEngine(), DEFAULT_ENGINE);
            if (!ownEngines.contains(engine)) continue;
            // 单次运行指定的电脑优先级最高；没有指定时才回落到项目的首选电脑。
            if (StringUtils.hasText(run.getRequestedRunnerId())) {
                if (!runner.getId().equals(run.getRequestedRunnerId())) continue;
            } else if (StringUtils.hasText(config.getPreferredRunnerId())
                    && !runner.getId().equals(config.getPreferredRunnerId())) {
                continue;
            }
            long projectActive = countRuns(new LambdaQueryWrapper<AiTaskRunEntity>()
                    .eq(AiTaskRunEntity::getProjectId, run.getProjectId())
                    .in(AiTaskRunEntity::getStatus, List.of("claimed", "running")));
            if (projectActive >= valueOr(config.getMaxParallelRuns(), 2)) continue;

            LocalDateTime now = LocalDateTime.now();
            run.setRunnerId(runner.getId());
            run.setStatus("running");
            run.setClaimedAt(now);
            run.setStartedAt(now);
            String continuationInput = run.getPendingInput();
            run.setPendingInput(null);
            appendProgress(run, "phase", runner.getName() + " 已领取任务，" + label(engine) + " 正在启动");
            setTaskStatus(run.getTaskId(), "AI 进行中");
            TaskEntity task = taskMapper.selectById(run.getTaskId());
            ProjectEntity project = projectMapper.selectById(run.getProjectId());
            notifyStarted(run, task, project, runner.getName());
            return new AiRunnerClaimDto(run.getId(), run.getTaskId(), task.getTitle(), run.getProjectId(),
                    project == null ? run.getProjectId() : project.getName(), engine, run.getPromptUrl(),
                    config.getRepoUrl(), blankOr(config.getDefaultBranch(), "main"),
                    blankOr(config.getWorkspaceDir(), defaultFolder(run.getProjectId())), run.getBranchName(),
                    previousFailure(run), run.getSessionId(), continuationInput,
                    accounts.runAccount(run.getAiAccountId(), engine));
        }
        return null;
    }

    /** 接收 Runner 筛选后的用户可读进展；命令、工具调用等类型不会被保存。 */
    @Transactional
    public synchronized void reportProgress(String token, String runId, ReportAiRunProgressRequest request) {
        AiRunnerNodeEntity runner = requireRunner(token);
        AiTaskRunEntity run = runMapper.selectById(runId);
        if (run == null) throw new NotFoundException("AI 运行不存在：" + runId);
        if (!runner.getId().equals(run.getRunnerId())) {
            throw new UnauthorizedException("该运行不属于当前执行节点");
        }
        if (!ACTIVE.contains(run.getStatus())) throw new BadRequestException("该运行已经结束，不能继续回传进展");
        String kind = trim(request.kind()).toLowerCase();
        if (!PROGRESS_KINDS.contains(kind)) throw new BadRequestException("不支持的进展类型：" + kind);
        String content = limit(request.content(), MAX_PROGRESS);
        if (!StringUtils.hasText(content) || content.equals(run.getLatestProgress())) return;
        appendProgress(run, kind, content);
        runner.setLastSeenAt(LocalDateTime.now());
        runnerMapper.updateById(runner);
    }

    /** 节点回传退出码和摘要；成功进入待验证，失败回到进行中。 */
    @Transactional
    public synchronized AiRunDto complete(String token, String runId, CompleteAiRunRequest request) {
        AiRunnerNodeEntity runner = requireRunner(token);
        AiTaskRunEntity run = runMapper.selectById(runId);
        if (run == null) throw new NotFoundException("AI 运行不存在：" + runId);
        if (!runner.getId().equals(run.getRunnerId())) {
            throw new UnauthorizedException("该运行不属于当前执行节点");
        }
        // 用户已暂停时，Runner 可能正好在回传退出结果；保留暂停结论，不让迟到回传覆盖它。
        if ("cancelled".equals(run.getStatus())) return toRunDtos(List.of(run)).get(0);
        int exitCode = request.exitCode() == null ? -1 : request.exitCode();
        run.setExitCode(exitCode);
        run.setStatus(exitCode == 0 ? "succeeded" : "failed");
        run.setResultSummary(limit(request.resultSummary(), MAX_RESULT));
        if (StringUtils.hasText(request.sessionId())) run.setSessionId(limit(request.sessionId(), 128));
        applyUsage(run, request);
        run.setFinishedAt(LocalDateTime.now());
        String engine = label(blankOr(run.getEngine(), DEFAULT_ENGINE));
        // 退出码 3 是 Runner 的约定：CLI 正常结束但没有报告任务状态，结果需要人判断，
        // 不能笼统地说成「执行失败」让人分不清到底成没成。
        String completion = exitCode == 0 ? engine + " 已完成执行，等待人工验收"
                : exitCode == 3 ? engine + " 已结束会话但未报告任务状态，请根据原始答复确认结果"
                : engine + " 执行未完成，请查看失败原因";
        appendProgress(run, "result", completion);
        setTaskStatus(run.getTaskId(), exitCode == 0 ? "待验证" : "进行中");
        runner.setLastSeenAt(LocalDateTime.now());
        runnerMapper.updateById(runner);
        notifyFinished(run, engine, exitCode);
        return toRunDtos(List.of(run)).get(0);
    }

    /**
     * 接收当前执行节点生成的单文件 HTML 报告，并把稳定公开地址关联到本次运行。
     *
     * <p>只有仍由该节点执行中的运行可以上传，避免其他节点或迟到请求篡改历史报告。
     */
    @Transactional
    public synchronized HostedPageDto saveReport(String token, String runId, MultipartFile html) {
        AiRunnerNodeEntity runner = requireRunner(token);
        AiTaskRunEntity run = runMapper.selectById(runId);
        if (run == null) throw new NotFoundException("AI 运行不存在：" + runId);
        if (!runner.getId().equals(run.getRunnerId())) {
            throw new UnauthorizedException("该运行不属于当前执行节点");
        }
        if (!ACTIVE.contains(run.getStatus())) {
            throw new BadRequestException("AI 运行已结束，不能再上传报告");
        }
        TaskEntity task = taskMapper.selectById(run.getTaskId());
        String taskTitle = task == null ? run.getTaskId() : task.getTitle();
        String scopeTitle = StringUtils.hasText(run.getSubId()) ? subTitle(run.getTaskId(), run.getSubId()) : null;
        String title = StringUtils.hasText(scopeTitle)
                ? taskTitle + " · " + scopeTitle + " · AI 报告"
                : taskTitle + " · AI 报告";
        title = limit(title, 200);
        var report = hostedPages.saveRunReport(runId, html, title, run.getRequestedBy());
        appendProgress(run, "message", "已生成持久化报告，可随时查看和分享");
        runner.setLastSeenAt(LocalDateTime.now());
        runnerMapper.updateById(runner);
        return report;
    }

    /** 用户暂停一条等待中或正在执行的 AI 运行。 */
    @Transactional
    public synchronized AiRunDto pause(String runId) {
        AiTaskRunEntity run = runMapper.selectById(runId);
        if (run == null) throw new NotFoundException("AI 运行不存在：" + runId);
        if (!ACTIVE.contains(run.getStatus())) {
            throw new BadRequestException("该 AI 运行已经结束，不能暂停");
        }
        run.setStatus("cancelled");
        run.setResultSummary("用户已暂停此 AI 任务");
        run.setFinishedAt(LocalDateTime.now());
        runMapper.updateById(run);
        appendProgress(run, "result", "用户已暂停 AI 任务，执行节点将停止本地执行");
        setTaskStatus(run.getTaskId(), "进行中");
        return toRunDtos(List.of(run)).get(0);
    }

    /** Runner 轮询自身已领取运行的取消状态。 */
    public boolean cancelled(String token, String runId) {
        AiRunnerNodeEntity runner = requireRunner(token);
        AiTaskRunEntity run = runMapper.selectById(runId);
        if (run == null) throw new NotFoundException("AI 运行不存在：" + runId);
        if (!runner.getId().equals(run.getRunnerId())) {
            throw new UnauthorizedException("该运行不属于当前执行节点");
        }
        return "cancelled".equals(run.getStatus());
    }

    /**
     * 跑完之后往飞书群里说一声。
     *
     * <p>验收是个「必须有人立刻看到」的动作：状态自己变成待验证是不会有人知道的，
     * 群里那张带跳转按钮的卡片才是真正把人叫过来的东西。通知失败只记日志 ——
     * 结果已经落库，不能因为发不出通知就把这次运行判成失败。
     */
    private void notifyFinished(AiTaskRunEntity run, String engineLabel, int exitCode) {
        try {
            TaskEntity task = taskMapper.selectById(run.getTaskId());
            ProjectEntity project = projectMapper.selectById(run.getProjectId());
            String scope = StringUtils.hasText(run.getSubId())
                    ? "子任务 · " + blankOr(subTitle(run.getTaskId(), run.getSubId()), run.getSubId())
                    : "整个任务";
            notifier.notifyRunFinished(run.getTaskId(), run.getProjectId(),
                    task == null ? run.getTaskId() : task.getTitle(),
                    project == null ? run.getProjectId() : project.getName(),
                    engineLabel, scope, exitCode == 0, exitCode == 3, run.getResultSummary());
        } catch (RuntimeException ex) {
            LOG.warn("AI 运行完成通知发送失败：{}", ex.getMessage());
        }
    }

    /** 派工（进入 / 回到执行队列）后的飞书通知；失败只记日志，不影响派工结果。 */
    private void notifyQueued(AiTaskRunEntity run) {
        try {
            TaskEntity task = taskMapper.selectById(run.getTaskId());
            ProjectEntity project = projectMapper.selectById(run.getProjectId());
            String scope = StringUtils.hasText(run.getSubId())
                    ? "子任务 · " + blankOr(subTitle(run.getTaskId(), run.getSubId()), run.getSubId())
                    : "整个任务";
            AiRunnerNodeEntity target = StringUtils.hasText(run.getRequestedRunnerId())
                    ? runnerMapper.selectById(run.getRequestedRunnerId()) : null;
            notifier.notifyRunQueued(run.getTaskId(), run.getProjectId(),
                    task == null ? run.getTaskId() : task.getTitle(),
                    project == null ? run.getProjectId() : project.getName(),
                    label(blankOr(run.getEngine(), DEFAULT_ENGINE)), scope,
                    target == null ? null : target.getName());
        } catch (RuntimeException ex) {
            LOG.warn("AI 派工通知发送失败：{}", ex.getMessage());
        }
    }

    /** 执行电脑领取任务（AI 开始执行）后的飞书通知；失败只记日志，不影响领取结果。 */
    private void notifyStarted(AiTaskRunEntity run, TaskEntity task, ProjectEntity project, String runnerName) {
        try {
            notifier.notifyRunStarted(run.getTaskId(), run.getProjectId(),
                    task == null ? run.getTaskId() : task.getTitle(),
                    project == null ? run.getProjectId() : project.getName(),
                    label(blankOr(run.getEngine(), DEFAULT_ENGINE)), runnerName);
        } catch (RuntimeException ex) {
            LOG.warn("AI 开始执行通知发送失败：{}", ex.getMessage());
        }
    }

    /**
     * 记下这次运行的模型与用量。
     *
     * <p>Runner 解析不出用量时会回传全 0，这种情况不覆盖已有数据——续跑会对同一条运行再回传
     * 一次，不能让一次统计失败把先前记好的账抹掉。
     */
    private void applyUsage(AiTaskRunEntity run, CompleteAiRunRequest request) {
        long input = positive(request.inputTokens());
        long output = positive(request.outputTokens());
        long cached = positive(request.cachedInputTokens());
        long total = request.totalTokens() == null ? input + output + cached
                : positive(request.totalTokens());
        boolean hasCost = request.costUsd() != null && request.costUsd().signum() > 0;
        if (total == 0 && !hasCost && !StringUtils.hasText(request.model())) return;
        // 续跑会在原有账单上累加，页面看到的是这条运行到目前为止的总消耗。
        run.setInputTokens(valueOr(run.getInputTokens(), 0L) + input);
        run.setOutputTokens(valueOr(run.getOutputTokens(), 0L) + output);
        run.setCachedInputTokens(valueOr(run.getCachedInputTokens(), 0L) + cached);
        run.setTotalTokens(valueOr(run.getTotalTokens(), 0L) + total);
        if (hasCost) {
            run.setCostUsd(valueOr(run.getCostUsd(), BigDecimal.ZERO).add(request.costUsd()));
        }
        if (StringUtils.hasText(request.model())) run.setModel(limit(request.model().trim(), 120));
        run.setModels(limit(mergeModels(run.getModels(), request.models()), 500));
    }

    /** 把续跑用到的模型并进已记录的列表，保持去重和稳定顺序。 */
    private static String mergeModels(String recorded, String reported) {
        Set<String> names = new java.util.TreeSet<>();
        for (String source : List.of(blankOr(recorded, ""), blankOr(reported, ""))) {
            for (String name : source.split(",")) {
                if (StringUtils.hasText(name)) names.add(name.trim());
            }
        }
        return names.isEmpty() ? null : String.join(",", names);
    }

    private static AiRunUsageDto toUsageDto(AiTaskRunEntity run) {
        long total = valueOr(run.getTotalTokens(), 0L);
        boolean recorded = total > 0 || run.getCostUsd() != null || StringUtils.hasText(run.getModel());
        return new AiRunUsageDto(run.getModel(), run.getModels(),
                valueOr(run.getInputTokens(), 0L), valueOr(run.getOutputTokens(), 0L),
                valueOr(run.getCachedInputTokens(), 0L), total, run.getCostUsd(), recorded);
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
                appendProgress(run, "phase", "执行电脑离线，任务已自动重新排队");
                setTaskStatus(run.getTaskId(), "进行中");
            }
        }
    }

    private List<AiRunDto> toRunDtos(List<AiTaskRunEntity> runs) {
        if (runs.isEmpty()) return List.of();
        List<String> runIds = runs.stream().map(AiTaskRunEntity::getId).toList();
        Map<String, List<AiRunProgressDto>> progressByRun = progressMapper.selectList(
                        new LambdaQueryWrapper<AiRunProgressEntity>()
                                .in(AiRunProgressEntity::getRunId, runIds)
                                .orderByAsc(AiRunProgressEntity::getCreatedAt))
                .stream().collect(Collectors.groupingBy(AiRunProgressEntity::getRunId,
                        Collectors.collectingAndThen(Collectors.toList(), items -> {
                            int from = Math.max(0, items.size() - MAX_PROGRESS_PER_RUN);
                            return items.subList(from, items.size()).stream()
                                    .map(item -> new AiRunProgressDto(item.getId(), item.getKind(),
                                            item.getContent(), text(item.getCreatedAt())))
                                    .toList();
                        })));
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
        Map<String, String> reportUrls = hostedPages.runReportUrls(runIds);
        return runs.stream().map(run -> {
            TaskEntity task = tasks.get(run.getTaskId());
            ProjectEntity project = projects.get(run.getProjectId());
            AiRunnerNodeEntity runner = StringUtils.hasText(run.getRunnerId())
                    ? runners.get(run.getRunnerId()) : null;
            return new AiRunDto(run.getId(), run.getTaskId(), task == null ? run.getTaskId() : task.getTitle(),
                    run.getSubId(),
                    StringUtils.hasText(run.getSubId()) ? subTitle(run.getTaskId(), run.getSubId()) : null,
                    run.getProjectId(), project == null ? run.getProjectId() : project.getName(),
                    blankOr(run.getEngine(), DEFAULT_ENGINE), run.getStatus(),
                    run.getRunnerId(), runnerName(run, runner), run.getRequestedRunnerId(), run.getRequestedBy(),
                    run.getBranchName(), run.getRetryOfRunId(),
                    run.getResultSummary(), reportUrls.get(run.getId()), run.getLatestProgress(),
                    text(run.getProgressUpdatedAt()),
                    progressByRun.getOrDefault(run.getId(), List.of()), toUsageDto(run),
                    run.getSessionId(), run.getExitCode(), text(run.getCreatedAt()),
                    text(run.getStartedAt()), text(run.getFinishedAt()));
        }).toList();
    }

    /** 历史运行的执行电脑名；电脑已被解除接入时说清楚，别显示成「未分配电脑」。 */
    private static String runnerName(AiTaskRunEntity run, AiRunnerNodeEntity runner) {
        if (runner != null) return runner.getName();
        return StringUtils.hasText(run.getRunnerId()) ? "已解除接入的电脑" : null;
    }

    private void appendProgress(AiTaskRunEntity run, String kind, String content) {
        LocalDateTime now = LocalDateTime.now();
        AiRunProgressEntity progress = new AiRunProgressEntity();
        progress.setId(id("ap"));
        progress.setRunId(run.getId());
        progress.setKind(kind);
        progress.setContent(limit(content, MAX_PROGRESS));
        progress.setCreatedAt(now);
        progressMapper.insert(progress);
        run.setLatestProgress(progress.getContent());
        run.setProgressUpdatedAt(now);
        runMapper.updateById(run);
    }

    /**
     * 供其他服务复用的 Runner 令牌校验。
     *
     * @return 对应的执行节点实体；令牌无效时抛出 {@link UnauthorizedException}
     */
    public AiRunnerNodeEntity authenticateRunner(String token) {
        return requireRunner(token);
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
        // 已完成的任务被重新派给 AI 时，旧的完成时间不再成立
        if (!TaskService.DONE.equals(status)) task.setCompletedAt(null);
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
                blankOr(entity.getDefaultEngine(), DEFAULT_ENGINE),
                entity.getPreferredRunnerId(), valueOr(entity.getMaxParallelRuns(), 2));
    }

    private AiWorkflowDto toWorkflow(String projectId, String projectName, AiProjectConfigDto config,
            boolean configured, boolean projectDeleted, Map<String, String> runnerNames,
            Map<String, List<AiTaskRunEntity>> runsByProject) {
        List<AiTaskRunEntity> runs = runsByProject.getOrDefault(projectId, List.of());
        String lastRunAt = runs.stream().map(AiTaskRunEntity::getCreatedAt).filter(Objects::nonNull)
                .max(LocalDateTime::compareTo).map(LocalDateTime::toString).orElse(null);
        return new AiWorkflowDto(projectId, projectName, config,
                config.preferredRunnerId() == null ? null : runnerNames.get(config.preferredRunnerId()),
                (int) runs.stream().filter(run -> ACTIVE.contains(run.getStatus())).count(),
                (int) runs.stream().filter(run -> "succeeded".equals(run.getStatus())).count(),
                (int) runs.stream().filter(run -> "failed".equals(run.getStatus())).count(),
                lastRunAt, configured, projectDeleted);
    }

    /** 是否已经有电脑（无论在线与否）接入了这种 AI 工具。 */
    private boolean anyRunnerSupports(String engine) {
        return runnerMapper.selectList(null).stream().anyMatch(runner -> engines(runner).contains(engine));
    }

    private static String requireEngine(String engine) {
        String value = trim(engine).toLowerCase();
        if (!ENGINES.contains(value)) throw new BadRequestException("不支持的 AI 工具：" + engine);
        return value;
    }

    private static List<String> engines(AiRunnerNodeEntity runner) {
        List<String> declared = runner.getEngines() == null ? List.of() : List.of(runner.getEngines().split(","));
        List<String> valid = declared.stream().map(String::trim).map(String::toLowerCase)
                .filter(ENGINES::contains).distinct().toList();
        // 老节点没有声明过 engines，按历史行为当作只会 Codex。
        return valid.isEmpty() ? List.of(DEFAULT_ENGINE) : valid;
    }

    private static String joinEngines(List<String> engines) {
        List<String> valid = engines == null ? List.of()
                : engines.stream().filter(Objects::nonNull).map(String::trim).map(String::toLowerCase)
                        .filter(ENGINES::contains).distinct().toList();
        return String.join(",", valid.isEmpty() ? List.of(DEFAULT_ENGINE) : valid);
    }

    private static String label(String engine) {
        return ENGINE_LABELS.getOrDefault(engine, engine);
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

    /** 计算节点实际生效的最大并发数：管理员配置优先，否则使用 Runner 自报值。 */
    private static int effectiveMaxConcurrency(AiRunnerNodeEntity runner) {
        return valueOr(runner.getAdminMaxConcurrency(), valueOr(runner.getMaxConcurrency(), 1));
    }

    private static long valueOr(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private static BigDecimal valueOr(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    /** 负数一律按 0 处理：节点回传的用量只能让账单变大，不能倒扣。 */
    private static long positive(Long value) {
        return value == null || value < 0 ? 0 : value;
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
