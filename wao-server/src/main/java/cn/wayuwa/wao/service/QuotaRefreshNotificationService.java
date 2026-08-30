package cn.wayuwa.wao.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.wayuwa.wao.domain.QuotaRefreshNotificationStateEntity;
import cn.wayuwa.wao.dto.AiSessionQuotaDto;
import cn.wayuwa.wao.dto.AiWeeklyQuotaDto;
import cn.wayuwa.wao.dto.CodexQuotaLimitDto;
import cn.wayuwa.wao.dto.CodexQuotaWindowDto;
import cn.wayuwa.wao.dto.NotificationSettingsDto.SystemPolicy;
import cn.wayuwa.wao.dto.RunnerCodexQuotaReport;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.QuotaRefreshNotificationStateMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 比较连续真实额度快照，并在剩余额度上升时向当前 WAO 群发送一次通知。 */
@Service
public class QuotaRefreshNotificationService {

    private static final Logger log = LoggerFactory.getLogger(QuotaRefreshNotificationService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter RESET_TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private static final TypeReference<List<QuotaPoint>> POINT_LIST = new TypeReference<>() { };

    private final QuotaRefreshNotificationStateMapper mapper;
    private final FeishuWebhookNotifier notifier;
    private final NotificationSettingsService notificationSettings;
    private final ObjectMapper json;
    private final String homeUrl;

    public QuotaRefreshNotificationService(QuotaRefreshNotificationStateMapper mapper,
            FeishuWebhookNotifier notifier, NotificationSettingsService notificationSettings,
            ObjectMapper json, @Value("${wao.share.base-url:}") String configuredBaseUrl) {
        this.mapper = mapper;
        this.notifier = notifier;
        this.notificationSettings = notificationSettings;
        this.json = json;
        this.homeUrl = StringUtils.hasText(configuredBaseUrl)
                ? configuredBaseUrl.strip().replaceFirst("/+$", "") : null;
    }

    /** 处理集中 OAuth 账号本轮同步成功的会话额度和周额度。 */
    @Transactional
    public void observeCentral(List<AiSessionQuotaDto> sessions, List<AiWeeklyQuotaDto> weeks) {
        Map<String, AccountSnapshot> accounts = new LinkedHashMap<>();
        if (sessions != null) {
            sessions.stream().filter(quota -> "SYNCED".equals(quota.status())).forEach(quota ->
                    account(accounts, "central:" + quota.accountId(), quota.label())
                            .points.add(new QuotaPoint("session", "本轮额度", quota.remainingPercent(),
                                    quota.resetsAt())));
        }
        if (weeks != null) {
            weeks.stream().filter(quota -> "SYNCED".equals(quota.status())).forEach(quota ->
                    account(accounts, "central:" + quota.accountId(), quota.label())
                            .points.add(new QuotaPoint("weekly", "周额度", quota.remainingPercent(),
                                    quota.resetsAt())));
        }
        accounts.forEach((sourceKey, snapshot) -> safelyObserve(sourceKey, snapshot.label, snapshot.points));
    }

    /** 处理 Runner 从 Codex 本地接口上报的一次脱敏真实额度。 */
    @Transactional
    public void observeRunner(String runnerId, RunnerCodexQuotaReport report) {
        if (report == null || report.limits() == null) return;
        String email = normalizeEmail(report.accountEmail());
        String sourceKey = email == null ? "runner:" + runnerId : "runner-account:" + email;
        String label = email == null ? "Codex · " + runnerId : email;
        List<QuotaPoint> points = new ArrayList<>();
        for (CodexQuotaLimitDto limit : report.limits()) {
            if (limit == null || limit.windows() == null) continue;
            for (CodexQuotaWindowDto window : limit.windows()) {
                if (window == null) continue;
                String duration = window.windowDurationMinutes() == null
                        ? "unknown" : window.windowDurationMinutes().toString();
                String limitId = StringUtils.hasText(limit.id()) ? limit.id() : limit.label();
                points.add(new QuotaPoint(limitId + ":" + duration,
                        windowLabel(limit.label(), window.windowDurationMinutes()),
                        100 - clamp(window.usedPercent()), resetText(window.resetsAt())));
            }
        }
        safelyObserve(sourceKey, label, points);
    }

    private void safelyObserve(String sourceKey, String accountLabel, List<QuotaPoint> points) {
        if (points == null || points.isEmpty()) return;
        try {
            observe(sourceKey, accountLabel, points);
        } catch (RuntimeException ex) {
            // 通知观测不能阻断额度保存、Runner 心跳或自主调度。
            log.warn("额度刷新通知处理失败，不影响额度业务链路：{}", ex.getClass().getSimpleName());
        }
    }

    private void observe(String sourceKey, String accountLabel, List<QuotaPoint> points) {
        List<QuotaPoint> current = points.stream()
                .filter(point -> point != null && StringUtils.hasText(point.key()))
                .map(point -> new QuotaPoint(point.key(), point.label(), clamp(point.remainingPercent()),
                        point.resetsAt()))
                .sorted(Comparator.comparing(QuotaPoint::key))
                .toList();
        if (current.isEmpty()) return;
        // 行锁覆盖比较、发送和基线推进，双 API 节点收到同一采样时也只会有一张群卡片。
        QuotaRefreshNotificationStateEntity state = mapper.selectForUpdate(sourceKey);
        if (state == null) {
            insertBaseline(sourceKey, accountLabel, current);
            return;
        }
        List<QuotaPoint> previous = read(state.getSnapshotJson());
        if (previous == null) {
            updateBaseline(state, accountLabel, current);
            return;
        }
        Map<String, QuotaPoint> oldByKey = new LinkedHashMap<>();
        previous.forEach(point -> oldByKey.put(point.key(), point));
        List<Increase> increases = current.stream()
                .filter(point -> oldByKey.containsKey(point.key()))
                .filter(point -> point.remainingPercent() > oldByKey.get(point.key()).remainingPercent())
                .map(point -> new Increase(oldByKey.get(point.key()), point))
                .toList();
        if (increases.isEmpty()) {
            updateBaseline(state, accountLabel, current);
            return;
        }
        // 处于全局关闭、沉默时段或自动化通知关闭时把事件视为已处理，不在窗口结束后补发旧消息。
        if (!notificationSettings.allows(SystemPolicy::automation)) {
            updateBaseline(state, accountLabel, current);
            return;
        }
        String changes = increases.stream().map(increase -> "- **" + increase.current.label() + "**："
                        + increase.previous.remainingPercent() + "% → " + increase.current.remainingPercent() + "%"
                        + resetSuffix(increase.current.resetsAt()))
                .collect(java.util.stream.Collectors.joining("\n"));
        FeishuWebhookNotifier.Card card = new FeishuWebhookNotifier.Card(
                "AI 额度已刷新", "green", List.of(List.of("账号", safeLabel(accountLabel))),
                changes + "\n\n额度来自最新真实采样，可以继续安排需要消耗额度的任务。",
                homeUrl == null ? null : "查看首页额度", homeUrl);
        if (notifier.notifyCard(card)) updateBaseline(state, accountLabel, current);
    }

    private void insertBaseline(String sourceKey, String accountLabel, List<QuotaPoint> points) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        QuotaRefreshNotificationStateEntity state = new QuotaRefreshNotificationStateEntity();
        state.setSourceKey(sourceKey);
        state.setAccountLabel(safeLabel(accountLabel));
        state.setSnapshotJson(write(points));
        state.setCreatedAt(now);
        state.setUpdatedAt(now);
        mapper.insert(state);
    }

    private void updateBaseline(QuotaRefreshNotificationStateEntity state, String accountLabel,
            List<QuotaPoint> points) {
        state.setAccountLabel(safeLabel(accountLabel));
        state.setSnapshotJson(write(points));
        state.setUpdatedAt(LocalDateTime.now(ZONE));
        mapper.updateById(state);
    }

    private String write(List<QuotaPoint> points) {
        try {
            return json.writeValueAsString(points);
        } catch (Exception ex) {
            throw new IllegalStateException("额度通知基线无法序列化", ex);
        }
    }

    private List<QuotaPoint> read(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return json.readValue(value, POINT_LIST);
        } catch (Exception ex) {
            return null;
        }
    }

    private static AccountSnapshot account(Map<String, AccountSnapshot> accounts, String key, String label) {
        return accounts.computeIfAbsent(key, unused -> new AccountSnapshot(safeLabel(label), new ArrayList<>()));
    }

    private static String windowLabel(String limitLabel, Long minutes) {
        String prefix = StringUtils.hasText(limitLabel) ? limitLabel.strip() : "Codex 额度";
        if (minutes == null) return prefix;
        if (minutes == 300L) return prefix + " · 本轮";
        if (minutes == 10_080L) return prefix + " · 周额度";
        return prefix + " · " + minutes + " 分钟窗口";
    }

    private static String resetText(Long epochSeconds) {
        if (epochSeconds == null || epochSeconds <= 0) return null;
        try {
            return Instant.ofEpochSecond(epochSeconds).toString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String resetSuffix(String resetsAt) {
        if (!StringUtils.hasText(resetsAt)) return "";
        try {
            return "，下次重置 " + Instant.parse(resetsAt).atZone(ZONE).format(RESET_TIME);
        } catch (RuntimeException ex) {
            try {
                return "，下次重置 " + LocalDateTime.parse(resetsAt).format(RESET_TIME);
            } catch (RuntimeException ignored) {
                return "";
            }
        }
    }

    private static String normalizeEmail(String value) {
        return StringUtils.hasText(value) ? value.strip().toLowerCase(java.util.Locale.ROOT) : null;
    }

    private static String safeLabel(String value) {
        String label = StringUtils.hasText(value) ? value.strip() : "AI 账号";
        return label.length() <= 255 ? label : label.substring(0, 255);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private record AccountSnapshot(String label, List<QuotaPoint> points) { }
    private record Increase(QuotaPoint previous, QuotaPoint current) { }
    private record QuotaPoint(String key, String label, int remainingPercent, String resetsAt) { }
}
