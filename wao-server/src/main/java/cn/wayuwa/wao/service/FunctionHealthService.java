package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.FunctionProductEntity;
import cn.wayuwa.wao.domain.FunctionUsageEventEntity;
import cn.wayuwa.wao.domain.FunctionUsageSummary;
import cn.wayuwa.wao.dto.FunctionHealthItemDto;
import cn.wayuwa.wao.dto.FunctionHealthOverviewDto;
import cn.wayuwa.wao.dto.FunctionIterationInput;
import cn.wayuwa.wao.mapper.FunctionIterationMapper;
import cn.wayuwa.wao.mapper.FunctionProductMapper;
import cn.wayuwa.wao.mapper.FunctionUsageEventMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 用真实页面打开与可核对提交维护 WAO 功能产品的持续迭代视图。 */
@Service
public class FunctionHealthService {
    private static final int WINDOW_DAYS = 30;
    private static final Set<String> RETIRED_FEATURES = Set.of(
            "personal-principles", "crawler-capabilities", "account-resources", "infrastructure",
            "ledger", "english-learning", "vocabulary", "food", "weekly-report", "devices",
            "games", "data-center", "chatgpt", "appearance", "memory", "ai-sessions",
            "updates", "action-plans", "workspaces");
    private static final List<String> FEATURES = List.of(
            "home", "navigation", "projects", "ideas", "decisions", "todo", "calendar", "workday-rhythm",
            "life-review", "knowledge", "skills", "skill-connections", "drawings", "files", "automation",
            "text-polish", "dashboard", "system-reports", "request-monitoring", "vault", "tianren", "settings");
    private static final Set<String> FEATURE_SET = Set.copyOf(FEATURES);
    private static final Map<String, String> SCOPE_FEATURE = Map.ofEntries(
            Map.entry("home", "home"), Map.entry("navigation", "navigation"),
            Map.entry("project", "projects"), Map.entry("projects", "projects"),
            Map.entry("task", "projects"), Map.entry("tasks", "projects"),
            Map.entry("ideas", "ideas"), Map.entry("decision", "decisions"),
            Map.entry("decisions", "decisions"), Map.entry("todo", "todo"), Map.entry("calendar", "calendar"),
            Map.entry("rhythm", "workday-rhythm"), Map.entry("workday", "workday-rhythm"),
            Map.entry("workday-rhythm", "workday-rhythm"),
            Map.entry("life-review", "life-review"), Map.entry("knowledge", "knowledge"), Map.entry("skill-market", "skills"),
            Map.entry("skills", "skills"), Map.entry("skill-connections", "skill-connections"),
            Map.entry("drawings", "drawings"), Map.entry("files", "files"),
            Map.entry("automation", "automation"), Map.entry("polish", "text-polish"), Map.entry("dashboard", "dashboard"),
            Map.entry("report", "system-reports"), Map.entry("monitoring", "request-monitoring"),
            Map.entry("http", "request-monitoring"),
            Map.entry("vault", "vault"), Map.entry("tianren", "tianren"),
            Map.entry("settings", "settings"));

    private final FunctionProductMapper products;
    private final FunctionUsageEventMapper usage;
    private final FunctionIterationMapper iterations;

    public FunctionHealthService(FunctionProductMapper products, FunctionUsageEventMapper usage,
            FunctionIterationMapper iterations) {
        this.products = products;
        this.usage = usage;
        this.iterations = iterations;
    }

    /** 记录当前成员真实打开一次功能；未知路由不会污染统计。 */
    public void recordOpen(String memberId, String featureId, String source) {
        requireFeature(featureId);
        if (!StringUtils.hasText(memberId)) throw new BadRequestException("无法识别当前成员");
        FunctionUsageEventEntity event = new FunctionUsageEventEntity();
        event.setMemberId(memberId);
        event.setFeatureId(featureId);
        event.setSource(Set.of("PAGE", "WORKBENCH", "SIDEBAR").contains(source) ? source : "PAGE");
        event.setOpenedAt(LocalDateTime.now());
        usage.insert(event);
    }

    /** 返回当前成员的 30 天使用度和全局产品分；无记录明确保持为零。 */
    public FunctionHealthOverviewDto overview(String memberId) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, FunctionUsageSummary> summaries = new LinkedHashMap<>();
        for (FunctionUsageSummary row : usage.summarize(memberId, now.minusDays(WINDOW_DAYS))) {
            if (FEATURE_SET.contains(row.getFeatureId())) summaries.put(row.getFeatureId(), row);
        }
        Map<String, FunctionProductEntity> states = new LinkedHashMap<>();
        for (FunctionProductEntity product : products.selectBatchIds(FEATURES)) {
            states.put(product.getFeatureId(), product);
        }
        long recentTotal = summaries.values().stream().mapToLong(row -> value(row.getRecentOpens())).sum();
        List<Long> activeOpens = FEATURES.stream().map(summaries::get).filter(Objects::nonNull)
                .map(row -> value(row.getRecentOpens())).filter(count -> count > 0).sorted().toList();
        double activeMedian = median(activeOpens);
        List<FunctionHealthItemDto> items = FEATURES.stream().map(featureId -> {
            FunctionUsageSummary summary = summaries.get(featureId);
            FunctionProductEntity state = states.get(featureId);
            long total = summary == null ? 0 : value(summary.getTotalOpens());
            long recent = summary == null ? 0 : value(summary.getRecentOpens());
            int iterationCount = state == null ? 0 : value(state.getIterationCount());
            int manual = state == null ? 0 : value(state.getManualAdjustment());
            int score = Math.max(1, Math.min(100, 1 + iterationCount + manual));
            String[] insight = insight(total, recent, activeMedian, score);
            return new FunctionHealthItemDto(featureId, score, iterationCount, manual, total, recent,
                    recentTotal == 0 ? 0 : recent * 100.0 / recentTotal,
                    summary == null ? null : summary.getLastOpenedAt(), insight[0], insight[1]);
        }).toList();
        return new FunctionHealthOverviewDto(WINDOW_DAYS, now, recentTotal, items);
    }

    /** 人工只做一分校准；默认分和提交加分仍由系统计算。 */
    @Transactional
    public FunctionHealthItemDto adjust(String memberId, String featureId, int delta) {
        requireFeature(featureId);
        if (delta != -1 && delta != 1) throw new BadRequestException("评分每次只能调整 1 分");
        products.ensure(featureId);
        products.adjust(featureId, delta);
        return overview(memberId).functions().stream().filter(item -> item.featureId().equals(featureId))
                .findFirst().orElseThrow();
    }

    /** 把 Runner 上报的提交 scope 幂等映射成产品迭代；无法确认归属的 scope 宁可跳过。 */
    @Transactional
    public void recordIterations(String runId, List<FunctionIterationInput> inputs) {
        if (inputs == null) return;
        for (FunctionIterationInput input : inputs.stream().limit(50).toList()) {
            if (input == null || !StringUtils.hasText(input.commitSha()) || !StringUtils.hasText(input.scope())) continue;
            String featureId = SCOPE_FEATURE.get(input.scope().trim().toLowerCase(Locale.ROOT));
            if (featureId == null || RETIRED_FEATURES.contains(featureId)) continue;
            products.ensure(featureId);
            String subject = StringUtils.hasText(input.subject()) ? input.subject().trim() : input.scope().trim();
            if (iterations.insertIgnore(runId, featureId, limit(input.commitSha(), 64), limit(subject, 255)) == 1) {
                products.incrementIteration(featureId);
            }
        }
    }

    private static String[] insight(long total, long recent, double median, int score) {
        if (total == 0) return new String[] {"UNKNOWN", "真实使用样本不足；继续观察，暂不建议下线"};
        if (recent == 0) return new String[] {"LOW", "当前账号近 30 天未打开；可先从个人导航移除。评估全站下线前，先核对其他成员使用和业务依赖"};
        if (recent < Math.max(2, median * 0.25)) return new String[] {"LOW", "当前账号近 30 天使用偏低；先优化入口或说明、检查重复能力，再观察一个周期决定是否合并或下线"};
        if (recent >= Math.max(5, median * 1.5) && score < 60) return new String[] {"HIGH", "当前账号使用较频繁但功能分未到 60，优先缩短高频操作路径"};
        if (score >= 90) return new String[] {"MATURE", "已达到 90 分，可评估制作独立功能介绍"};
        if (score >= 60) return new String[] {"STABLE", "已达到 60 分，可暂缓迭代并观察真实使用变化"};
        return new String[] {"NORMAL", "保持观察，后续提交会自动累计迭代分"};
    }

    private static double median(List<Long> sortedValues) {
        int size = sortedValues.size();
        if (size == 0) return 0;
        int middle = size / 2;
        return size % 2 == 0
                ? (sortedValues.get(middle - 1) + sortedValues.get(middle)) / 2.0
                : sortedValues.get(middle);
    }

    private static long value(Long value) { return value == null ? 0 : value; }
    private static int value(Integer value) { return value == null ? 0 : value; }
    private static String limit(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
    private static void requireFeature(String featureId) {
        if (!FEATURE_SET.contains(featureId)) throw new BadRequestException("未知功能：" + featureId);
    }
}
