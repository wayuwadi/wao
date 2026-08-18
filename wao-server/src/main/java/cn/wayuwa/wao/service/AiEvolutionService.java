package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.AiEvolutionStateEntity;
import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.dto.AiEvolutionOverviewDto;
import cn.wayuwa.wao.dto.AiEvolutionSuggestionDto;
import cn.wayuwa.wao.dto.AiRunDto;
import cn.wayuwa.wao.dto.AiSessionQuotaDto;
import cn.wayuwa.wao.dto.AiWeeklyQuotaDto;
import cn.wayuwa.wao.dto.AiWorkflowDto;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.AiEvolutionStateMapper;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import cn.wayuwa.wao.service.AiAssistantAssetService.TaskMiningContext;
import cn.wayuwa.wao.service.AiAssistantAssetService.TaskMiningSource;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 以真实周额度为门槛，把系统改进建议接入既有自主目标与 Runner 链路。 */
@Service
public class AiEvolutionService {

    private static final Logger log = LoggerFactory.getLogger(AiEvolutionService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String STATE_ID = "autonomous-evolution";
    private static final int MIN_REMAINING_PERCENT = 50;
    private static final int DEFAULT_INTERVAL_HOURS = 12;
    private static final int DEFAULT_BATCH_SIZE = 1;
    private static final int MIN_INTERVAL_HOURS = 1;
    private static final int MAX_INTERVAL_HOURS = 168;
    private static final int MAX_BATCH_SIZE = 5;
    private static final int CHECK_MINUTES = 15;
    private static final int MAX_SUGGESTION_COUNT = 5;
    private static final String USER_VALUE_CONTRACT = "用户价值验收：本次交付必须以用户可直接使用的新能力、"
            + "操作提效或体验改善为主要结果，并用真实用户链路说明改动前后的差异；"
            + "测试、文档、重构和依赖升级只能作为支撑，不能单独作为交付。"
            + "如果没有可靠数据源或无法在单个 PR 内产出可感知结果，不要伪造功能，按约定跳过并说明原因。";

    private final AiAccountService accounts;
    private final AiRunService runs;
    private final AiTaskRunMapper runMapper;
    private final AiEvolutionStateMapper stateMapper;
    private final FeishuWebhookNotifier notifier;
    private final AiEvolutionSuggestionRepository suggestions;
    private final AiAssistantService assistant;
    private final AiAssistantAssetService assets;
    private final ObjectMapper json;
    private final String publicBaseUrl;
    private final CodexQuotaService codexQuotas;

    public AiEvolutionService(AiAccountService accounts, AiRunService runs, AiTaskRunMapper runMapper,
            AiEvolutionStateMapper stateMapper, FeishuWebhookNotifier notifier,
            AiEvolutionSuggestionRepository suggestions, AiAssistantService assistant,
            AiAssistantAssetService assets, ObjectMapper json,
            CodexQuotaService codexQuotas,
            @Value("${wao.share.base-url:}") String configuredBaseUrl) {
        this.accounts = accounts;
        this.runs = runs;
        this.runMapper = runMapper;
        this.stateMapper = stateMapper;
        this.notifier = notifier;
        this.suggestions = suggestions;
        this.assistant = assistant;
        this.assets = assets;
        this.json = json;
        this.codexQuotas = codexQuotas;
        this.publicBaseUrl = configuredBaseUrl == null || configuredBaseUrl.isBlank()
                ? null : configuredBaseUrl.replaceFirst("/+$", "");
    }

    /** 首页读取真实会话/周额度、推荐目标和最近一次自动检查结果。 */
    public AiEvolutionOverviewDto overview() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        List<AiSessionQuotaDto> sessionQuotas = accounts.sessionQuotas();
        List<AiWeeklyQuotaDto> quotas = accounts.weeklyQuotas();
        LocalDateTime lastActivity = runMapper.latestActivityAt();
        boolean active = hasActiveRun();
        AiEvolutionStateEntity state = stateMapper.selectById(STATE_ID);
        int intervalHours = intervalHours(state);
        int batchSize = batchSize(state);
        LocalDateTime eligibleAt = lastActivity == null ? now : lastActivity.plusHours(intervalHours);
        List<AiEvolutionSuggestionDto> currentSuggestions = suggestions.latest();
        boolean quotaReady = quotas.stream().anyMatch(quota -> "SYNCED".equals(quota.status())
                && quota.remainingPercent() > MIN_REMAINING_PERCENT);
        String status;
        String message;
        if (active) {
            status = "busy";
            message = "当前已有 AI 任务在运行，自动进化会等它们结束后重新计时。";
        } else if (lastActivity != null && eligibleAt.isAfter(now)) {
            status = "cooling_down";
            message = "最近 " + intervalHours + " 小时内有 AI 任务，暂不自动派发。";
        } else if (!quotaReady) {
            status = "quota_unavailable";
            boolean hasSyncedQuota = quotas.stream().anyMatch(quota -> "SYNCED".equals(quota.status()));
            message = quotas.isEmpty() ? "尚未获取到可解析的真实周额度，自动进化保持暂停。"
                    : !hasSyncedQuota ? "当前只有旧的周额度快照，本次同步未成功，自动进化保持暂停。"
                    : "真实周剩余额度未高于 50%，自动进化保持暂停。";
        } else if (publicBaseUrl == null) {
            status = "quota_unavailable";
            message = "未配置公网任务分享地址，自动进化保持暂停；手动建议仍可直接执行。";
        } else if (currentSuggestions.isEmpty()) {
            status = "quota_unavailable";
            message = "还没有可自动派发的真实建议，生成可靠建议后才会开始调度。";
        } else {
            status = "ready";
            message = "已满足 " + intervalHours + " 小时空闲与周剩余额度门槛，下次检查会自动选择最多 "
                    + batchSize + " 项改进。";
        }
        if (state != null && state.getLastOutcome() != null && !state.getLastOutcome().isBlank()) {
            message += " 最近检查：" + state.getLastOutcome();
        }
        LocalDateTime nextTriggerAt = estimateNextTriggerAt(now, eligibleAt, state,
                !active && quotaReady && publicBaseUrl != null && !currentSuggestions.isEmpty());
        return new AiEvolutionOverviewDto(codexQuotas.listAccounts(), sessionQuotas, quotas,
                currentSuggestions, status, message,
                text(lastActivity), text(eligibleAt), text(nextTriggerAt),
                state == null ? null : text(state.getLastCheckedAt()),
                state == null ? null : state.getLastRunId(), intervalHours, batchSize);
    }

    /**
     * 保存人工设置的自动执行间隔和单次任务数。
     *
     * <p>保存后把下次检查时间推进到当前时刻，使缩短后的频率无需再等待旧检查窗口。
     *
     * @param intervalHours 距离最近 AI 活动至少空闲多少小时，范围 1 到 168
     * @param batchSize 单次最多派发多少项真实建议，范围 1 到 5
     */
    public void saveSchedule(int intervalHours, int batchSize) {
        if (intervalHours < MIN_INTERVAL_HOURS || intervalHours > MAX_INTERVAL_HOURS) {
            throw new BadRequestException("自动执行间隔必须在 1 到 168 小时之间");
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new BadRequestException("单次任务数必须在 1 到 5 之间");
        }
        LocalDateTime now = LocalDateTime.now(ZONE);
        int updated = stateMapper.update(null, new LambdaUpdateWrapper<AiEvolutionStateEntity>()
                .eq(AiEvolutionStateEntity::getId, STATE_ID)
                .set(AiEvolutionStateEntity::getIntervalHours, intervalHours)
                .set(AiEvolutionStateEntity::getBatchSize, batchSize)
                .set(AiEvolutionStateEntity::getNextCheckAt, now)
                .set(AiEvolutionStateEntity::getLastOutcome,
                        "已更新为每 " + intervalHours + " 小时最多派发 " + batchSize + " 个任务")
                .set(AiEvolutionStateEntity::getUpdatedAt, now));
        if (updated == 0) throw new IllegalStateException("自主进化调度状态不存在");
    }

    /** 从真实知识库和飞书同步归档挖掘并保存一批最多五条的新候选任务。 */
    public synchronized List<AiEvolutionSuggestionDto> refreshSuggestions() {
        TaskMiningContext context = assets.taskMiningContext();
        if (context.sources().isEmpty()) {
            throw new BadRequestException("知识库和飞书同步归档中还没有可读取的真实资料，无法生成任务建议");
        }
        List<AiEvolutionSuggestionDto> previous = suggestions.latest();
        String answer = assistant.runReadOnlyJob("evolution-suggestions",
                suggestionPrompt(context, previous));
        List<AiEvolutionSuggestionDto> generated = parseSuggestions(answer, context, previous);
        suggestions.saveBatch(id("eb"), generated);
        return generated;
    }

    /** 用户点击一条建议后，使用选中的真实工作流创建任务并立即入队。 */
    public AiRunDto launchSuggestion(String suggestionId, String projectId, String requestedBy, String requestBaseUrl) {
        AiEvolutionSuggestionDto suggestion = suggestions.find(suggestionId);
        if (suggestion == null) throw new BadRequestException("这条改进建议不存在或已下线");
        return runs.launchGoal(projectId, withUserValueContract(suggestion.goal()), requestedBy, requestBaseUrl);
    }

    /**
     * 双节点安全地检查一次自动派发条件。
     *
     * <p>先原子推进 {@code nextCheckAt}；只有一个节点抢占成功才会访问额度供应商和创建任务。
     */
    public void checkAndLaunch() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        int claimed = stateMapper.update(null, new LambdaUpdateWrapper<AiEvolutionStateEntity>()
                .eq(AiEvolutionStateEntity::getId, STATE_ID)
                .le(AiEvolutionStateEntity::getNextCheckAt, now)
                .set(AiEvolutionStateEntity::getNextCheckAt, now.plusMinutes(CHECK_MINUTES))
                .set(AiEvolutionStateEntity::getLastCheckedAt, now)
                .set(AiEvolutionStateEntity::getUpdatedAt, now));
        if (claimed == 0) return;
        AiEvolutionStateEntity schedule = stateMapper.selectById(STATE_ID);
        int intervalHours = intervalHours(schedule);
        int batchSize = batchSize(schedule);

        try {
            accounts.refreshQuotaSnapshots();
            List<AiWeeklyQuotaDto> syncedQuotas = accounts.weeklyQuotas().stream()
                    .filter(quota -> "SYNCED".equals(quota.status()))
                    .toList();
            notifyLowQuota(syncedQuotas);

            if (hasActiveRun()) {
                outcome("已有 AI 任务在运行，本轮跳过", null, null);
                return;
            }
            LocalDateTime lastActivity = runMapper.latestActivityAt();
            if (lastActivity != null && lastActivity.plusHours(intervalHours).isAfter(now)) {
                outcome("距离最近一次 AI 任务尚不足 " + intervalHours + " 小时", null, null);
                return;
            }

            List<AiWeeklyQuotaDto> quotas = syncedQuotas.stream()
                    .filter(quota -> quota.remainingPercent() > MIN_REMAINING_PERCENT)
                    .toList();
            if (quotas.isEmpty()) {
                outcome("没有周剩余额度高于 50% 的真实账号", null, null);
                return;
            }
            if (publicBaseUrl == null) {
                outcome("未配置公网任务分享地址，无法安全自动派发", null, null);
                return;
            }

            List<AiEvolutionSuggestionDto> availableSuggestions = suggestions.latest();
            if (availableSuggestions.isEmpty()) {
                outcome("尚未从真实知识库或飞书归档生成任务建议", null, null);
                return;
            }
            LaunchCandidate candidate = chooseWorkflow(quotas);
            if (candidate == null) {
                outcome("没有与可用额度账号匹配的 WAO 工作流", null, null);
                return;
            }
            List<AiEvolutionSuggestionDto> selected = nextSuggestions(availableSuggestions,
                    schedule == null ? null : schedule.getLastSuggestionId(), batchSize);
            String lastRunId = null;
            String lastSuggestionId = null;
            List<String> launchedTitles = new ArrayList<>();
            String failedReason = null;
            for (AiEvolutionSuggestionDto suggestion : selected) {
                try {
                    AiRunDto run = runs.launchGoal(candidate.workflow.projectId(),
                            withUserValueContract(suggestion.goal()), "system",
                            publicBaseUrl, candidate.quota.accountId());
                    lastRunId = run.id();
                    lastSuggestionId = suggestion.id();
                    launchedTitles.add("「" + suggestion.title() + "」");
                } catch (RuntimeException ex) {
                    failedReason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                    break;
                }
            }
            if (launchedTitles.isEmpty()) {
                throw new BadRequestException("首个任务派发失败：" + shorten(failedReason == null ? "未知错误" : failedReason));
            }
            String message = "已自动派发 " + launchedTitles.size() + " 个任务：" + String.join("、", launchedTitles);
            if (failedReason != null) message += "；后续任务派发失败：" + shorten(failedReason);
            outcome(message, lastRunId, lastSuggestionId);
        } catch (RuntimeException ex) {
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            outcome("自动派发失败：" + shorten(reason), null, null);
            log.warn("自主进化检查失败：{}", reason);
        }
    }

    /** 每个账号的同一个额度重置周期只提醒一次，发送失败时保留下轮重试机会。 */
    private void notifyLowQuota(List<AiWeeklyQuotaDto> syncedQuotas) {
        List<AiWeeklyQuotaDto> lowQuotas = syncedQuotas.stream()
                .filter(quota -> quota.remainingPercent() < MIN_REMAINING_PERCENT)
                .toList();
        if (lowQuotas.isEmpty()) return;
        AiEvolutionStateEntity state = stateMapper.selectById(STATE_ID);
        java.util.Set<String> remindedKeys = state == null || state.getLastLowQuotaKey() == null
                ? java.util.Set.of()
                : new java.util.HashSet<>(java.util.Arrays.asList(state.getLastLowQuotaKey().split("\\|")));
        List<AiWeeklyQuotaDto> newLowQuotas = lowQuotas.stream()
                .filter(quota -> !remindedKeys.contains(reminderKey(quota)))
                .toList();
        if (newLowQuotas.isEmpty()) return;

        String body = newLowQuotas.stream()
                .map(quota -> "- **" + quota.label() + "**：周剩余 " + quota.remainingPercent() + "%"
                        + (quota.resetsAt() == null ? "" : "，重置时间 " + quota.resetsAt()))
                .collect(java.util.stream.Collectors.joining("\n"));
        FeishuWebhookNotifier.Card card = new FeishuWebhookNotifier.Card(
                "AI 周额度低于 50%", "orange", List.of(),
                body + "\n\n自主进化已对这些账号暂停，请按需调整使用节奏。",
                publicBaseUrl == null ? null : "查看首页额度",
                publicBaseUrl);
        if (notifier.notifyCard(card)) {
            java.util.Set<String> currentReminderKeys = new java.util.HashSet<>(remindedKeys);
            syncedQuotas.forEach(quota -> {
                currentReminderKeys.removeIf(key -> key.startsWith(quota.accountId() + "@"));
                String key = reminderKey(quota);
                if (remindedKeys.contains(key)
                        || newLowQuotas.stream().anyMatch(newLow -> reminderKey(newLow).equals(key))) {
                    currentReminderKeys.add(key);
                }
            });
            String storedReminderKeys = currentReminderKeys.stream()
                    .sorted()
                    .collect(java.util.stream.Collectors.joining("|"));
            stateMapper.update(null, new LambdaUpdateWrapper<AiEvolutionStateEntity>()
                    .eq(AiEvolutionStateEntity::getId, STATE_ID)
                    .set(AiEvolutionStateEntity::getLastLowQuotaKey, storedReminderKeys)
                    .set(AiEvolutionStateEntity::getUpdatedAt, LocalDateTime.now(ZONE)));
        }
    }

    private static String reminderKey(AiWeeklyQuotaDto quota) {
        String window = quota.resetsAt();
        if (window == null) {
            window = "week:" + java.time.LocalDate.now(ZONE)
                    .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        }
        return quota.accountId() + "@" + window;
    }

    private LaunchCandidate chooseWorkflow(List<AiWeeklyQuotaDto> quotas) {
        AiWorkflowDto workflow = runs.listWorkflows().stream()
                .filter(candidate -> candidate.config().enabled())
                .filter(candidate -> quotas.stream().anyMatch(quota ->
                        quota.provider().equals(candidate.config().defaultEngine())))
                .sorted((left, right) -> Integer.compare(workflowScore(right), workflowScore(left)))
                .findFirst().orElse(null);
        if (workflow == null) return null;
        AiWeeklyQuotaDto quota = quotas.stream()
                .filter(item -> item.provider().equals(workflow.config().defaultEngine()))
                .max(java.util.Comparator.comparingInt(AiWeeklyQuotaDto::remainingPercent)).orElse(null);
        return quota == null ? null : new LaunchCandidate(workflow, quota);
    }

    private static int workflowScore(AiWorkflowDto workflow) {
        String name = workflow.projectName().toLowerCase(Locale.ROOT);
        String repo = workflow.config().repoUrl().toLowerCase(Locale.ROOT);
        return (repo.contains("wayuwacn/wao") ? 10 : 0) + (name.contains("wao") || name.contains("all in one") ? 5 : 0);
    }

    private void outcome(String message, String runId, String suggestionId) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        LambdaUpdateWrapper<AiEvolutionStateEntity> update = new LambdaUpdateWrapper<AiEvolutionStateEntity>()
                .eq(AiEvolutionStateEntity::getId, STATE_ID)
                .set(AiEvolutionStateEntity::getLastOutcome, shorten(message))
                .set(AiEvolutionStateEntity::getUpdatedAt, now);
        if (runId != null) update.set(AiEvolutionStateEntity::getLastRunId, runId);
        if (suggestionId != null) update.set(AiEvolutionStateEntity::getLastSuggestionId, suggestionId);
        stateMapper.update(null, update);
    }

    private boolean hasActiveRun() {
        Long count = runMapper.selectCount(new LambdaQueryWrapper<AiTaskRunEntity>()
                .in(AiTaskRunEntity::getStatus, List.of("queued", "claimed", "running")));
        return count != null && count > 0;
    }

    private static List<AiEvolutionSuggestionDto> nextSuggestions(
            List<AiEvolutionSuggestionDto> suggestions, String previousId, int limit) {
        int previous = -1;
        for (int i = 0; i < suggestions.size(); i++) {
            if (suggestions.get(i).id().equals(previousId)) previous = i;
        }
        List<AiEvolutionSuggestionDto> selected = new ArrayList<>();
        for (int i = 1; i <= Math.min(limit, suggestions.size()); i++) {
            selected.add(suggestions.get((previous + i) % suggestions.size()));
        }
        return List.copyOf(selected);
    }

    private static int intervalHours(AiEvolutionStateEntity state) {
        return state == null || state.getIntervalHours() == null
                ? DEFAULT_INTERVAL_HOURS : state.getIntervalHours();
    }

    private static int batchSize(AiEvolutionStateEntity state) {
        return state == null || state.getBatchSize() == null
                ? DEFAULT_BATCH_SIZE : state.getBatchSize();
    }

    private static LocalDateTime estimateNextTriggerAt(LocalDateTime now, LocalDateTime eligibleAt,
            AiEvolutionStateEntity state, boolean conditionsAvailable) {
        if (!conditionsAvailable) return null;
        LocalDateTime checkAt = state == null || state.getNextCheckAt() == null
                ? now : state.getNextCheckAt();
        if (checkAt.isBefore(now)) checkAt = now;
        while (checkAt.isBefore(eligibleAt)) checkAt = checkAt.plusMinutes(CHECK_MINUTES);
        return checkAt;
    }

    private static String suggestionPrompt(TaskMiningContext context,
            List<AiEvolutionSuggestionDto> previous) {
        String existingTasks = context.openTaskTitles().isEmpty() ? "（无）"
                : context.openTaskTitles().stream().limit(100)
                        .map(title -> "- " + title).collect(java.util.stream.Collectors.joining("\n"));
        String previousTitles = previous.isEmpty() ? "（无）"
                : previous.stream().map(item -> "- " + item.title())
                        .collect(java.util.stream.Collectors.joining("\n"));
        return """
                请从下面的 WAO 真实资料里挖掘最多 5 条尚未进入执行的产品需求或开发任务候选。

                规则：
                1. 每条必须由某一个 source 的明确内容支持，站在“给当前系统增加或完善真实功能”的需求/任务角度表达。
                2. 不得把闲聊、已经完成的结果、纯愿望口号、通用代码巡检或资料中不存在的能力包装成任务。
                3. 优先选择用户可直接使用的新功能、能减少重复操作的提效能力或能恢复工作的体验改善；纯测试补齐、纯文档修订、纯重构、依赖升级和其它只有开发者能感知的技术优化不能作为候选。
                4. 避开“现有未完成任务”和“上一批建议”；同一批之间标题与目标也不得重复。
                5. 标题不超过 40 字；description 用不超过 120 字说清用户结果；goal 不超过 1200 字，写成可直接交给开发 AI 的完整中文目标，包含要解决的问题、可观察结果、真实性边界和验收重点。
                6. sourceIndex 必须是下方真实存在的来源编号。证据不足 5 条时返回所有仍有明确依据的候选，至少 1 条即可成功换批；一条可靠候选也没有时才返回 error，绝不能用纯技术优化凑数。
                7. 只输出一个 JSON 对象，不要 Markdown 代码围栏或额外解释：
                {"suggestions":[{"title":"...","description":"...","goal":"...","sourceIndex":1}]}
                如果一条可靠候选也没有：{"error":"具体原因","suggestions":[]}

                现有未完成任务：
                """ + existingTasks + """


                上一批建议：
                """ + previousTitles + "\n\n" + context.promptContext();
    }

    private List<AiEvolutionSuggestionDto> parseSuggestions(String response,
            TaskMiningContext context, List<AiEvolutionSuggestionDto> previous) {
        JsonNode root;
        try {
            String payload = jsonPayload(response);
            root = json.readTree(payload);
        } catch (Exception ex) {
            throw new BadRequestException("本地 Codex 返回的任务建议格式无法解析，请重试换一批");
        }
        JsonNode items = root.path("suggestions");
        if (!items.isArray()) {
            throw new BadRequestException("本地 Codex 返回的任务建议格式不可靠，请重试换一批");
        }
        if (items.size() > MAX_SUGGESTION_COUNT) {
            throw new BadRequestException("本地 Codex 返回了超过 5 条任务建议，请重试换一批");
        }
        if (items.isEmpty()) {
            String reason = clean(root.path("error").asText(""));
            throw new BadRequestException(reason.isBlank()
                    ? "真实资料中未能挖掘出可靠的新任务，请补充资料后重试"
                    : "真实资料暂未提供可靠的新任务线索：" + shorten(reason));
        }

        Set<String> previousTitles = previous.stream().map(item -> normalized(item.title()))
                .collect(java.util.stream.Collectors.toSet());
        Set<String> titles = new HashSet<>();
        List<AiEvolutionSuggestionDto> generated = new ArrayList<>();
        for (JsonNode item : items) {
            String title = required(item, "title", 40);
            String description = required(item, "description", 120);
            String goal = required(item, "goal", 1_200);
            int sourceIndex = item.path("sourceIndex").asInt(-1);
            TaskMiningSource source = context.sources().stream()
                    .filter(candidate -> candidate.index() == sourceIndex).findFirst()
                    .orElseThrow(() -> new BadRequestException("任务建议引用了不存在的资料来源，请重试换一批"));
            String titleKey = normalized(title);
            if (!titles.add(titleKey)) throw new BadRequestException("本地 Codex 返回了重复任务，请重试换一批");
            if (previousTitles.contains(titleKey)) {
                throw new BadRequestException("本地 Codex 重复了上一批任务，请再次刷新");
            }
            String groundedGoal = withUserValueContract(title + "\n\n任务线索来自 WAO 真实资产「"
                    + source.title() + "」（" + source.url() + "）。\n\n"
                    + goal + "\n\n开始实现前请先核对来源线索与当前代码、规格和已有任务；"
                    + "若真实数据或官方接口不支持，不要制造演示数据或占位能力。");
            generated.add(new AiEvolutionSuggestionDto(id("es"), title, description,
                    "chat".equals(source.type()) ? "ri-chat-3-line" : "ri-book-open-line",
                    groundedGoal, source.title(), source.url()));
        }
        return List.copyOf(generated);
    }

    private static String required(JsonNode item, String field, int maxLength) {
        String value = clean(item.path(field).asText(""));
        if (value.isBlank()) throw new BadRequestException("任务建议缺少字段 " + field + "，请重试换一批");
        if (value.length() > maxLength) {
            throw new BadRequestException("任务建议字段 " + field + " 过长，请重试换一批");
        }
        return value;
    }

    private static String jsonPayload(String response) {
        if (response == null) return "";
        String value = response.trim();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end >= start ? value.substring(start, end + 1) : value;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String normalized(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private static String withUserValueContract(String goal) {
        return goal.contains("用户价值验收：") ? goal : goal + "\n\n" + USER_VALUE_CONTRACT;
    }

    private static String shorten(String value) {
        return value.length() <= 500 ? value : value.substring(0, 499) + "…";
    }

    private static String text(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private record LaunchCandidate(AiWorkflowDto workflow, AiWeeklyQuotaDto quota) {
    }
}
