package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.SystemSettingEntity;
import cn.wayuwa.wao.dto.AgyQuotaAccountDto;
import cn.wayuwa.wao.dto.AgyQuotaBucketDto;
import cn.wayuwa.wao.dto.AgyQuotaGroupDto;
import cn.wayuwa.wao.dto.AgyQuotaReport;
import cn.wayuwa.wao.mapper.SystemSettingMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 汇总本机采集或受控上报的脱敏 Agy 额度快照，按账号去重并触发刷新通知。 */
@Service
public class AgyQuotaService {
    private static final Logger log = LoggerFactory.getLogger(AgyQuotaService.class);
    static final String PREFIX = "agy-quota-v1:";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_GROUPS = 10;
    private static final int MAX_BUCKETS_PER_GROUP = 6;
    private static final long MAX_EPOCH_SECONDS = 253_402_300_799L;

    private final SystemSettingMapper settings;
    private final ObjectMapper json;
    private final QuotaRefreshNotificationService notifications;
    private final boolean enabled;
    private final String executable;
    private final String source;
    private final String accountLabel;
    private long nextRead;
    private final ReentrantLock sampling = new ReentrantLock();

    record Snapshot(String source, String observedAt, boolean failed, AgyQuotaReport report) {
    }

    public AgyQuotaService(
            SystemSettingMapper settings,
            ObjectMapper json,
            QuotaRefreshNotificationService notifications,
            @Value("${wao.agy-quota.enabled:false}") boolean enabled,
            @Value("${wao.agy-quota.executable:agy}") String executable,
            @Value("${wao.agy-quota.source:local}") String source,
            @Value("${wao.agy-quota.account-label:Agy}") String accountLabel) {
        validateSource(source);
        this.settings = settings;
        this.json = json;
        this.notifications = notifications;
        this.enabled = enabled;
        this.executable = executable;
        this.source = source;
        this.accountLabel = normalizeAccountLabel(accountLabel);
    }

    /** 显式启用本机采集后每分钟采样一次；全局调度关闭时由独立调度调用，查询也会节流补采。 */
    @Scheduled(fixedDelay = 60000, initialDelay = 15000)
    public void refresh() {
        if (!enabled || !sampling.tryLock()) return;
        try {
            refreshLocked();
        } finally {
            sampling.unlock();
        }
    }

    private void refreshLocked() {
        if (System.currentTimeMillis() < nextRead) return;
        nextRead = System.currentTimeMillis() + 60000;
        SystemSettingEntity row = settings.selectById(PREFIX + source);
        Snapshot previous = row == null ? null : decode(row.getCfgValue());
        Snapshot snapshot;
        try {
            AgyQuotaReport read = sanitize(readLocal());
            snapshot = new Snapshot(source, Instant.now().toString(), false, read);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            snapshot = new Snapshot(
                    source,
                    previous == null ? null : previous.observedAt(),
                    true,
                    previous == null ? null : previous.report());
        }
        persist(PREFIX + source, row, snapshot);
        if (!snapshot.failed() && snapshot.report() != null) {
            safelyNotify("local:" + source, snapshot.report());
        }
    }

    /** 接收受控远程工具或管理员上报的脱敏 Agy 额度快照。 */
    public AgyQuotaAccountDto report(String reportSource, AgyQuotaReport report, Instant observedAt) {
        validateSource(reportSource);
        if (report == null) throw new BadRequestException("Agy 额度上报内容不能为空");
        AgyQuotaReport safe = sanitize(report);
        Instant timestamp = observedAt == null ? Instant.now() : observedAt;
        String key = PREFIX + reportSource;
        SystemSettingEntity row = settings.selectById(key);
        Snapshot snapshot = new Snapshot(reportSource, timestamp.toString(), false, safe);
        persist(key, row, snapshot);
        safelyNotify("report:" + reportSource, safe);
        String normalizedKey = accountKey(safe);
        return new AgyQuotaAccountDto(normalizedKey, safe.accountLabel(), safe.groups(),
                List.of(reportSource), timestamp.toString(), true);
    }

    AgyQuotaReport readLocal() throws Exception {
        return LocalAgyQuotaProcess.read(executable, accountLabel, Duration.ofSeconds(20));
    }

    /** 合并同一账号标识的最新真实快照，不累加多机额度；失败或过期值明确为历史。 */
    public List<AgyQuotaAccountDto> listAccounts() {
        refresh();
        var candidates = new ArrayList<AgyQuotaAccountDto>();
        for (var row : settings.selectList(new QueryWrapper<SystemSettingEntity>().likeRight("cfg_key", PREFIX))) {
            Snapshot snapshot = decode(row.getCfgValue());
            if (snapshot == null || snapshot.report() == null || snapshot.observedAt() == null) continue;
            AgyQuotaReport report;
            try {
                report = sanitize(snapshot.report());
            } catch (BadRequestException ignored) {
                continue;
            }
            Instant at = timestamp(snapshot.observedAt());
            if (at.equals(Instant.EPOCH)) continue;
            boolean fresh = !snapshot.failed()
                    && at.isAfter(Instant.now().minusSeconds(900))
                    && !at.isAfter(Instant.now().plusSeconds(60));
            candidates.add(new AgyQuotaAccountDto(
                    accountKey(report),
                    report.accountLabel(),
                    report.groups(),
                    List.of(snapshot.source()),
                    snapshot.observedAt(),
                    fresh));
        }
        var grouped = new LinkedHashMap<String, AgyQuotaAccountDto>();
        for (var account : candidates) {
            var old = grouped.get(account.key());
            if (old == null) {
                grouped.put(account.key(), account);
                continue;
            }
            var latest = timestamp(account.lastSyncedAt()).isAfter(timestamp(old.lastSyncedAt())) ? account : old;
            var sources = new TreeSet<>(old.sources());
            sources.addAll(account.sources());
            grouped.put(account.key(), new AgyQuotaAccountDto(
                    account.key(),
                    latest.accountLabel(),
                    latest.groups(),
                    List.copyOf(sources),
                    latest.lastSyncedAt(),
                    latest.fresh()));
        }
        if (grouped.isEmpty() && enabled) {
            throw new BadRequestException("尚未读到本机 Agy 额度，请检查 Agy 登录和网络后重试");
        }
        return List.copyOf(grouped.values());
    }

    private void persist(String cfgKey, SystemSettingEntity existing, Snapshot snapshot) {
        try {
            boolean insert = existing == null;
            SystemSettingEntity row = insert ? new SystemSettingEntity() : existing;
            if (insert) row.setCfgKey(cfgKey);
            row.setCfgValue(json.writeValueAsString(snapshot));
            row.setUpdatedAt(LocalDateTime.now(ZONE));
            if (insert) settings.insert(row);
            else settings.updateById(row);
        } catch (Exception ignored) {
            throw new BadRequestException("Agy 额度快照保存失败，请稍后重试");
        }
    }

    private void safelyNotify(String sourceName, AgyQuotaReport report) {
        try {
            notifications.observeAgy(sourceName, report);
        } catch (RuntimeException error) {
            log.warn("Agy 额度通知处理失败，不影响额度采集：{}", error.getClass().getSimpleName());
        }
    }

    static AgyQuotaReport sanitize(AgyQuotaReport report) {
        String label = normalizeAccountLabel(report.accountLabel());
        List<AgyQuotaGroupDto> groups = report.groups() == null ? List.of() : report.groups().stream()
                .filter(Objects::nonNull)
                .limit(MAX_GROUPS)
                .map(AgyQuotaService::sanitizeGroup)
                .filter(group -> !group.buckets().isEmpty())
                .toList();
        if (groups.isEmpty()) {
            throw new BadRequestException("Agy 额度快照至少需要一个有效的模型分组和额度窗口");
        }
        return new AgyQuotaReport(label, groups);
    }

    private static AgyQuotaGroupDto sanitizeGroup(AgyQuotaGroupDto group) {
        String name = limit(group.name(), 100);
        if (!StringUtils.hasText(name)) return new AgyQuotaGroupDto("Agy", List.of());
        List<AgyQuotaBucketDto> buckets = group.buckets() == null ? List.of() : group.buckets().stream()
                .filter(Objects::nonNull)
                .limit(MAX_BUCKETS_PER_GROUP)
                .map(AgyQuotaService::sanitizeBucket)
                .filter(Objects::nonNull)
                .toList();
        return new AgyQuotaGroupDto(name, buckets);
    }

    private static AgyQuotaBucketDto sanitizeBucket(AgyQuotaBucketDto bucket) {
        String id = limit(bucket.id(), 64);
        String name = limit(bucket.name(), 100);
        String window = limit(bucket.window(), 32);
        if (!StringUtils.hasText(id) && !StringUtils.hasText(window)) return null;
        int remaining = Math.max(0, Math.min(100, bucket.remainingPercent()));
        Long resetsAt = bucket.resetsAt() != null && bucket.resetsAt() > 0 && bucket.resetsAt() <= MAX_EPOCH_SECONDS
                ? bucket.resetsAt()
                : null;
        return new AgyQuotaBucketDto(id, name, window, remaining, resetsAt);
    }

    private static void validateSource(String value) {
        if (!StringUtils.hasText(value) || !value.matches("[a-zA-Z0-9_-]{1,40}")) {
            throw new BadRequestException("Agy 额度来源须为 1 到 40 位字母、数字、下划线或短横线");
        }
    }

    private static String normalizeAccountLabel(String value) {
        if (!StringUtils.hasText(value)) return "Agy";
        String trimmed = value.strip();
        return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120);
    }

    private static String accountKey(AgyQuotaReport report) {
        return normalizeAccountLabel(report.accountLabel()).toLowerCase(Locale.ROOT);
    }

    private static String limit(String value, int max) {
        if (!StringUtils.hasText(value)) return null;
        String trimmed = value.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private Snapshot decode(String value) {
        try {
            return json.readValue(value, Snapshot.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Instant timestamp(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(value).atZone(ZONE).toInstant();
            } catch (Exception invalid) {
                return Instant.EPOCH;
            }
        }
    }
}
