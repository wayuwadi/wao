package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import cn.wayuwa.wao.domain.CodexRunnerQuotaEntity;
import cn.wayuwa.wao.dto.CodexQuotaAccountDto;
import cn.wayuwa.wao.dto.CodexQuotaCreditsDto;
import cn.wayuwa.wao.dto.CodexQuotaLimitDto;
import cn.wayuwa.wao.dto.CodexQuotaWindowDto;
import cn.wayuwa.wao.dto.RunnerCodexQuotaReport;
import cn.wayuwa.wao.mapper.AiRunnerNodeMapper;
import cn.wayuwa.wao.mapper.CodexRunnerQuotaMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 汇总各执行电脑通过 Codex 本地接口上报的脱敏额度，不接收或保存登录凭据。 */
@Service
public class CodexQuotaService {

    private static final int MAX_LIMITS = 20;
    private static final int MAX_WINDOWS = 3;
    private static final int FRESH_MINUTES = 15;

    private final CodexRunnerQuotaMapper quotaMapper;
    private final AiRunnerNodeMapper runnerMapper;
    private final ObjectMapper json;
    private final QuotaRefreshNotificationService quotaNotifications;
    private final AiQuotaSnapshotService quotaSnapshots;

    public CodexQuotaService(CodexRunnerQuotaMapper quotaMapper, AiRunnerNodeMapper runnerMapper,
            ObjectMapper json, QuotaRefreshNotificationService quotaNotifications,
            AiQuotaSnapshotService quotaSnapshots) {
        this.quotaMapper = quotaMapper;
        this.runnerMapper = runnerMapper;
        this.json = json;
        this.quotaNotifications = quotaNotifications;
        this.quotaSnapshots = quotaSnapshots;
    }

    /**
     * 保存一次 Runner 本地额度检查。
     *
     * <p>成功时替换结构化快照；失败时只记录原因并保留上一次真实值，避免短时网络错误把首页清空。
     */
    public void report(String runnerId, RunnerCodexQuotaReport report, String error, LocalDateTime now) {
        if (report == null && !StringUtils.hasText(error)) return;
        CodexRunnerQuotaEntity entity = quotaMapper.selectById(runnerId);
        boolean inserting = entity == null;
        if (inserting) {
            entity = new CodexRunnerQuotaEntity();
            entity.setRunnerId(runnerId);
        }
        entity.setLastCheckedAt(now);
        if (report != null) {
            RunnerCodexQuotaReport safe = sanitize(report);
            if (!safe.limits().isEmpty()) {
                entity.setAccountEmail(safe.accountEmail());
                entity.setPlan(safe.plan());
                entity.setSnapshotJson(write(safe));
                entity.setErrorMessage(null);
                entity.setLastSuccessAt(now);
            } else {
                entity.setErrorMessage("Codex 本地接口没有返回可解析的限额窗口");
            }
        } else {
            entity.setErrorMessage(limit(error.strip(), 500));
        }
        if (inserting) quotaMapper.insert(entity); else quotaMapper.updateById(entity);
        if (report != null && entity.getErrorMessage() == null) {
            RunnerCodexQuotaReport safe = sanitize(report);
            quotaSnapshots.recordRunner(runnerId, safe, now);
            quotaNotifications.observeRunner(runnerId, safe);
        }
    }

    /** 首页按账号合并多台电脑的最近真实快照，并标明来源电脑与新鲜度。 */
    public List<CodexQuotaAccountDto> listAccounts() {
        List<CodexRunnerQuotaEntity> snapshots = quotaMapper.selectList(null).stream()
                .filter(item -> StringUtils.hasText(item.getSnapshotJson()) && item.getLastSuccessAt() != null)
                .toList();
        if (snapshots.isEmpty()) return List.of();
        Map<String, AiRunnerNodeEntity> runners = new LinkedHashMap<>();
        for (AiRunnerNodeEntity runner : runnerMapper.selectList(null)) runners.put(runner.getId(), runner);

        Map<String, List<CodexRunnerQuotaEntity>> grouped = new LinkedHashMap<>();
        snapshots.stream()
                .sorted(Comparator.comparing(CodexRunnerQuotaEntity::getLastSuccessAt).reversed())
                .forEach(item -> grouped.computeIfAbsent(accountKey(item), unused -> new ArrayList<>()).add(item));

        LocalDateTime freshCutoff = LocalDateTime.now().minusMinutes(FRESH_MINUTES);
        LocalDateTime onlineCutoff = LocalDateTime.now().minusMinutes(2);
        List<CodexQuotaAccountDto> result = new ArrayList<>();
        for (Map.Entry<String, List<CodexRunnerQuotaEntity>> entry : grouped.entrySet()) {
            List<CodexRunnerQuotaEntity> group = entry.getValue();
            CodexRunnerQuotaEntity latest = group.get(0);
            RunnerCodexQuotaReport report = read(latest.getSnapshotJson());
            if (report == null || report.limits() == null || report.limits().isEmpty()) continue;
            report = sanitize(report);
            List<String> names = group.stream()
                    .map(item -> runners.get(item.getRunnerId()))
                    .filter(java.util.Objects::nonNull)
                    .map(AiRunnerNodeEntity::getName)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .sorted()
                    .toList();
            boolean online = group.stream().map(item -> runners.get(item.getRunnerId()))
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(runner -> runner.getLastSeenAt() != null && runner.getLastSeenAt().isAfter(onlineCutoff));
            boolean fresh = online && latest.getLastSuccessAt().isAfter(freshCutoff);
            result.add(new CodexQuotaAccountDto(entry.getKey(), report.accountEmail(), report.plan(),
                    report.credits(), report.limits(), names, latest.getLastSuccessAt().toString(), fresh));
        }
        return result;
    }

    private RunnerCodexQuotaReport sanitize(RunnerCodexQuotaReport report) {
        String email = nullableLimit(report.accountEmail(), 255);
        if (email != null) email = email.toLowerCase(Locale.ROOT);
        String plan = nullableLimit(report.plan(), 32);
        CodexQuotaCreditsDto credits = report.credits() == null ? null : new CodexQuotaCreditsDto(
                report.credits().hasCredits(), report.credits().unlimited(), balance(report.credits().balance()));
        List<CodexQuotaLimitDto> limits = report.limits() == null ? List.of() : report.limits().stream()
                .filter(java.util.Objects::nonNull)
                .limit(MAX_LIMITS)
                .map(this::sanitizeLimit)
                .filter(item -> !item.windows().isEmpty())
                .toList();
        return new RunnerCodexQuotaReport(email, plan, credits, limits);
    }

    private CodexQuotaLimitDto sanitizeLimit(CodexQuotaLimitDto limit) {
        String id = nullableLimit(limit.id(), 64);
        String label = nullableLimit(limit.label(), 100);
        List<CodexQuotaWindowDto> windows = limit.windows() == null ? List.of() : limit.windows().stream()
                .filter(java.util.Objects::nonNull)
                .limit(MAX_WINDOWS)
                .map(window -> new CodexQuotaWindowDto(
                        Math.max(0, Math.min(100, window.usedPercent())),
                        window.windowDurationMinutes() == null || window.windowDurationMinutes() <= 0
                                ? null : Math.min(window.windowDurationMinutes(), 525_600L),
                        window.resetsAt() == null || window.resetsAt() <= 0 ? null : window.resetsAt()))
                .toList();
        return new CodexQuotaLimitDto(id == null ? "codex" : id,
                label == null ? "通用使用限额" : label, windows);
    }

    private String accountKey(CodexRunnerQuotaEntity entity) {
        return StringUtils.hasText(entity.getAccountEmail())
                ? entity.getAccountEmail().strip().toLowerCase(Locale.ROOT)
                : "runner:" + entity.getRunnerId();
    }

    private String write(RunnerCodexQuotaReport report) {
        try {
            return json.writeValueAsString(report);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Codex 额度快照序列化失败", ex);
        }
    }

    private RunnerCodexQuotaReport read(String value) {
        try {
            return json.readValue(value, RunnerCodexQuotaReport.class);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static String balance(String value) {
        String safe = nullableLimit(value, 40);
        return safe != null && safe.matches("[-+]?\\d+(?:\\.\\d+)?") ? safe : null;
    }

    private static String nullableLimit(String value, int max) {
        if (!StringUtils.hasText(value)) return null;
        return limit(value.strip(), max);
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
