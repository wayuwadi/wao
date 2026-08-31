package cn.wayuwa.wao.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import cn.wayuwa.wao.domain.AiQuotaSnapshotEntity;
import cn.wayuwa.wao.dto.AiSessionQuotaDto;
import cn.wayuwa.wao.dto.AiWeeklyQuotaDto;
import cn.wayuwa.wao.dto.CodexQuotaLimitDto;
import cn.wayuwa.wao.dto.CodexQuotaWindowDto;
import cn.wayuwa.wao.dto.RunnerCodexQuotaReport;
import cn.wayuwa.wao.mapper.AiQuotaSnapshotMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 把集中账号和 Runner 的真实额度采样统一写成不含凭据的历史点。 */
@Service
public class AiQuotaSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(AiQuotaSnapshotService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final AiQuotaSnapshotMapper mapper;

    public AiQuotaSnapshotService(AiQuotaSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    /** 保存一个集中 OAuth 账号本轮成功同步的真实会话/周额度。 */
    public void recordCentral(String accountId, String provider, String label,
            AiSessionQuotaDto session, AiWeeklyQuotaDto weekly, LocalDateTime observedAt) {
        try {
            if (session != null && "SYNCED".equals(session.status())) {
                insert("central:" + accountId, label, provider, "session", "五小时会话额度",
                        session.remainingPercent(), parseTime(session.resetsAt()), observedAt);
            }
            if (weekly != null && "SYNCED".equals(weekly.status())) {
                insert("central:" + accountId, label, provider, "weekly", "七天额度",
                        weekly.remainingPercent(), parseTime(weekly.resetsAt()), observedAt);
            }
        } catch (RuntimeException ex) {
            // 历史统计失败不能让已成功的供应商额度同步回滚。
            log.warn("集中 AI 额度历史记录失败，不影响当前额度：{}", ex.getClass().getSimpleName());
        }
    }

    /** 保存 Runner 本地接口上报的一次脱敏真实额度。 */
    public void recordRunner(String runnerId, RunnerCodexQuotaReport report, LocalDateTime observedAt) {
        if (report == null || report.limits() == null) return;
        try {
            String email = normalizeEmail(report.accountEmail());
            String sourceKey = email == null ? "runner:" + runnerId : "runner-account:" + email;
            String label = email == null ? "Codex · " + runnerId : email;
            for (CodexQuotaLimitDto limit : report.limits()) {
                if (limit == null || limit.windows() == null) continue;
                for (CodexQuotaWindowDto window : limit.windows()) {
                    if (window == null) continue;
                    String duration = window.windowDurationMinutes() == null
                            ? "unknown" : window.windowDurationMinutes().toString();
                    String limitId = StringUtils.hasText(limit.id()) ? limit.id() : limit.label();
                    insert(sourceKey, label, "codex", limitId + ":" + duration,
                            windowLabel(limit.label(), window.windowDurationMinutes()),
                            100 - clamp(window.usedPercent()), resetTime(window.resetsAt()), observedAt);
                }
            }
        } catch (RuntimeException ex) {
            // 历史统计失败不能阻断 Runner 心跳和最新额度保存。
            log.warn("Runner AI 额度历史记录失败，不影响当前额度：{}", ex.getClass().getSimpleName());
        }
    }

    private void insert(String sourceKey, String accountLabel, String provider, String windowKey,
            String windowLabel, int remainingPercent, LocalDateTime resetsAt, LocalDateTime observedAt) {
        AiQuotaSnapshotEntity entity = new AiQuotaSnapshotEntity();
        entity.setSourceKey(limit(sourceKey, 320));
        entity.setAccountLabel(limit(StringUtils.hasText(accountLabel) ? accountLabel.strip() : "AI 账号", 255));
        entity.setProvider(limit(StringUtils.hasText(provider) ? provider.strip() : "unknown", 24));
        entity.setWindowKey(limit(StringUtils.hasText(windowKey) ? windowKey.strip() : "quota", 128));
        entity.setWindowLabel(limit(StringUtils.hasText(windowLabel) ? windowLabel.strip() : "使用额度", 128));
        entity.setRemainingPercent(clamp(remainingPercent));
        entity.setResetsAt(resetsAt);
        entity.setObservedAt(observedAt == null ? LocalDateTime.now(ZONE) : observedAt);
        mapper.insert(entity);
    }

    private static LocalDateTime parseTime(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return LocalDateTime.parse(value);
        } catch (RuntimeException ignored) {
            try {
                return Instant.parse(value).atZone(ZONE).toLocalDateTime();
            } catch (RuntimeException ignoredAgain) {
                return null;
            }
        }
    }

    private static LocalDateTime resetTime(Long epochSeconds) {
        if (epochSeconds == null || epochSeconds <= 0) return null;
        try {
            return Instant.ofEpochSecond(epochSeconds).atZone(ZONE).toLocalDateTime();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String windowLabel(String label, Long minutes) {
        String prefix = StringUtils.hasText(label) ? label.strip() : "Codex 额度";
        if (minutes == null) return prefix;
        if (minutes == 300L) return prefix + " · 五小时";
        if (minutes == 10_080L) return prefix + " · 七天";
        return prefix + " · " + minutes + " 分钟窗口";
    }

    private static String normalizeEmail(String value) {
        return StringUtils.hasText(value) ? value.strip().toLowerCase(Locale.ROOT) : null;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
