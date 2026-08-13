package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.AiEvolutionStateEntity;
import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.dto.AiEvolutionOverviewDto;
import cn.wayuwa.wao.dto.AiEvolutionSuggestionDto;
import cn.wayuwa.wao.dto.AiRunDto;
import cn.wayuwa.wao.dto.AiWeeklyQuotaDto;
import cn.wayuwa.wao.dto.AiWorkflowDto;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.AiEvolutionStateMapper;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
    private static final int IDLE_HOURS = 12;
    private static final int CHECK_MINUTES = 15;

    private static final List<AiEvolutionSuggestionDto> SUGGESTIONS = List.of(
            new AiEvolutionSuggestionDto("ux-friction", "找出并消除一个高频使用阻力",
                    "从真实首页、任务或 AI Run 链路中选择一个可复现的体验问题，完成修复和验收。",
                    "ri-magic-line",
                    "请对 WAO 当前真实用户链路做一次小范围体验走查，选择一个高频、可复现且能在单个 PR 内完成的使用阻力。先阅读相关规格和现有测试，说明根因后直接修复；覆盖加载、成功、失败和窄屏等相关状态，完成与风险相称的自动化测试和真实链路验收。不要扩大到产品重构，不要制造演示数据。"),
            new AiEvolutionSuggestionDto("test-gap", "补上一个最有价值的测试缺口",
                    "检查近期核心链路和历史回归点，补齐一个能真实拦截问题的测试或修复。",
                    "ri-test-tube-line",
                    "请检查 WAO 近期变更、核心业务链路和现有测试，找出一个最可能导致真实回归但尚未被覆盖的测试缺口。若能复现缺陷则一并修复；补充能在 CI 中稳定运行的测试，执行相关测试并说明它具体拦住什么回归。不要为了覆盖率写无行为价值的断言，不要改动无关模块。"),
            new AiEvolutionSuggestionDto("docs-drift", "消除一处规格与实现漂移",
                    "核对规格、ADR、API 契约和代码，修正一处会误导后续开发的真实偏差。",
                    "ri-file-search-line",
                    "请核对 WAO 的规格、ADR、API 契约与当前实现，选择一处会实际误导使用或后续开发的漂移。以真实实现条件和产品目标为准，修正代码或文档并补充必要验证；接口、数据结构或部署行为有变化时同步契约。不要只做措辞美化，不要覆盖已有决策历史。"),
            new AiEvolutionSuggestionDto("maintainability", "偿还一笔小而确定的技术债",
                    "从重复逻辑、错误状态或可维护性问题中选一项，保持行为可验证。",
                    "ri-tools-line",
                    "请在 WAO 当前代码中选择一笔小而确定、能在单个 PR 内偿还的技术债，优先处理重复且容易不一致的逻辑、含糊的错误状态或已有 TODO 对应的真实风险。保持外部行为兼容，必要时先补回归测试再重构，并执行相关验证。不要做大规模改名或纯格式整理。")
    );

    private final AiAccountService accounts;
    private final AiRunService runs;
    private final AiTaskRunMapper runMapper;
    private final AiEvolutionStateMapper stateMapper;
    private final FeishuWebhookNotifier notifier;
    private final String publicBaseUrl;

    public AiEvolutionService(AiAccountService accounts, AiRunService runs, AiTaskRunMapper runMapper,
            AiEvolutionStateMapper stateMapper, FeishuWebhookNotifier notifier,
            @Value("${wao.share.base-url:}") String configuredBaseUrl) {
        this.accounts = accounts;
        this.runs = runs;
        this.runMapper = runMapper;
        this.stateMapper = stateMapper;
        this.notifier = notifier;
        this.publicBaseUrl = configuredBaseUrl == null || configuredBaseUrl.isBlank()
                ? null : configuredBaseUrl.replaceFirst("/+$", "");
    }

    /** 首页读取真实周额度、推荐目标和最近一次自动检查结果。 */
    public AiEvolutionOverviewDto overview() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        List<AiWeeklyQuotaDto> quotas = accounts.weeklyQuotas();
        LocalDateTime lastActivity = runMapper.latestActivityAt();
        boolean active = hasActiveRun();
        LocalDateTime eligibleAt = lastActivity == null ? now : lastActivity.plusHours(IDLE_HOURS);
        boolean quotaReady = quotas.stream().anyMatch(quota -> "SYNCED".equals(quota.status())
                && quota.remainingPercent() >= MIN_REMAINING_PERCENT);
        AiEvolutionStateEntity state = stateMapper.selectById(STATE_ID);

        String status;
        String message;
        if (active) {
            status = "busy";
            message = "当前已有 AI 任务在运行，自动进化会等它们结束后重新计时。";
        } else if (lastActivity != null && eligibleAt.isAfter(now)) {
            status = "cooling_down";
            message = "最近 12 小时内有 AI 任务，暂不自动派发。";
        } else if (!quotaReady) {
            status = "quota_unavailable";
            boolean hasSyncedQuota = quotas.stream().anyMatch(quota -> "SYNCED".equals(quota.status()));
            message = quotas.isEmpty() ? "尚未获取到可解析的真实周额度，自动进化保持暂停。"
                    : !hasSyncedQuota ? "当前只有旧的周额度快照，本次同步未成功，自动进化保持暂停。"
                    : "真实周剩余额度低于 50%，自动进化保持暂停。";
        } else if (publicBaseUrl == null) {
            status = "quota_unavailable";
            message = "未配置公网任务分享地址，自动进化保持暂停；手动建议仍可直接执行。";
        } else {
            status = "ready";
            message = "已满足 12 小时空闲与周剩余额度门槛，下次检查会自动选择一项改进。";
        }
        if (state != null && state.getLastOutcome() != null && !state.getLastOutcome().isBlank()) {
            message += " 最近检查：" + state.getLastOutcome();
        }
        return new AiEvolutionOverviewDto(quotas, SUGGESTIONS, status, message,
                text(lastActivity), text(eligibleAt), state == null ? null : text(state.getLastCheckedAt()),
                state == null ? null : state.getLastRunId());
    }

    /** 用户点击一条建议后，使用选中的真实工作流创建任务并立即入队。 */
    public AiRunDto launchSuggestion(String suggestionId, String projectId, String requestedBy, String requestBaseUrl) {
        AiEvolutionSuggestionDto suggestion = suggestion(suggestionId);
        return runs.launchGoal(projectId, suggestion.goal(), requestedBy, requestBaseUrl);
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
            if (lastActivity != null && lastActivity.plusHours(IDLE_HOURS).isAfter(now)) {
                outcome("距离最近一次 AI 任务尚不足 12 小时", null, null);
                return;
            }

            List<AiWeeklyQuotaDto> quotas = syncedQuotas.stream()
                    .filter(quota -> quota.remainingPercent() >= MIN_REMAINING_PERCENT)
                    .toList();
            if (quotas.isEmpty()) {
                outcome("没有周剩余额度不低于 50% 的真实账号", null, null);
                return;
            }
            if (publicBaseUrl == null) {
                outcome("未配置公网任务分享地址，无法安全自动派发", null, null);
                return;
            }

            AiEvolutionStateEntity state = stateMapper.selectById(STATE_ID);
            AiEvolutionSuggestionDto suggestion = nextSuggestion(state == null ? null : state.getLastSuggestionId());
            LaunchCandidate candidate = chooseWorkflow(quotas);
            if (candidate == null) {
                outcome("没有与可用额度账号匹配的 WAO 工作流", null, suggestion.id());
                return;
            }
            AiRunDto run = runs.launchGoal(candidate.workflow.projectId(), suggestion.goal(), "system",
                    publicBaseUrl, candidate.quota.accountId());
            outcome("已自动派发「" + suggestion.title() + "」", run.id(), suggestion.id());
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

    private static AiEvolutionSuggestionDto suggestion(String id) {
        return SUGGESTIONS.stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new BadRequestException("这条改进建议不存在或已下线"));
    }

    private static AiEvolutionSuggestionDto nextSuggestion(String previousId) {
        int previous = -1;
        for (int i = 0; i < SUGGESTIONS.size(); i++) {
            if (SUGGESTIONS.get(i).id().equals(previousId)) previous = i;
        }
        return SUGGESTIONS.get((previous + 1) % SUGGESTIONS.size());
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
