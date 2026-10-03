package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.SystemSettingEntity;
import cn.wayuwa.wao.dto.CodexQuotaAccountDto;
import cn.wayuwa.wao.dto.RunnerCodexQuotaReport;
import cn.wayuwa.wao.mapper.SystemSettingMapper;
import cn.wayuwa.wao.common.BadRequestException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 独立于旧 Runner 的本机只读采样；数据库只保存展示字段，跨机器同邮箱去重。 */
@Service
public class LocalCodexQuotaService {
    static final String PREFIX = "codex-local-quota-v1:";
    private final SystemSettingMapper settings;
    private final ObjectMapper json;
    private final boolean enabled;
    private final String executable;
    private final String source;
    private long nextRead;
    private final java.util.concurrent.locks.ReentrantLock sampling = new java.util.concurrent.locks.ReentrantLock();
    record Snapshot(String source, String observedAt, boolean failed, RunnerCodexQuotaReport report) { }

    public LocalCodexQuotaService(SystemSettingMapper settings, ObjectMapper json,
            @Value("${wao.codex-quota.enabled:false}") boolean enabled,
            @Value("${wao.codex-quota.executable:codex}") String executable,
            @Value("${wao.codex-quota.source:local}") String source) {
        if (!source.matches("[a-zA-Z0-9_-]{1,40}")) throw new IllegalArgumentException("额度来源须为 1 到 40 位字母、数字或短横线");
        this.settings = settings; this.json = json; this.enabled = enabled; this.executable = executable; this.source = source;
    }

    /** 每分钟采样一次；查询也会补采，统一关闭调度时不会启动后台任务。 */
    @Scheduled(fixedDelay = 60000, initialDelay = 10000)
    public void refresh() {
        if (!enabled || !sampling.tryLock()) return;
        try { refreshLocked(); } finally { sampling.unlock(); }
    }

    private void refreshLocked() {
        if (System.currentTimeMillis() < nextRead) return;
        nextRead = System.currentTimeMillis() + 60000;
        SystemSettingEntity row = settings.selectById(PREFIX + source);
        Snapshot previous = row == null ? null : decode(row.getCfgValue());
        Snapshot snapshot;
        try {
            snapshot = new Snapshot(source, Instant.now().toString(), false, readLocal());
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            snapshot = new Snapshot(source, previous == null ? null : previous.observedAt(), true, previous == null ? null : previous.report());
        }
        try {
            boolean insert = row == null;
            if (insert) { row = new SystemSettingEntity(); row.setCfgKey(PREFIX + source); }
            row.setCfgValue(json.writeValueAsString(snapshot)); row.setUpdatedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
            if (insert) settings.insert(row); else settings.updateById(row);
        } catch (Exception ignored) { throw new BadRequestException("额度快照保存失败，请稍后重试"); }
    }

    RunnerCodexQuotaReport readLocal() throws Exception { return LocalCodexQuotaProcess.read(executable, Duration.ofSeconds(8)); }

    /** 合并同一邮箱的最新快照，不累加多机额度；失败或过期值明确为历史。 */
    public List<CodexQuotaAccountDto> merge(List<CodexQuotaAccountDto> existing) {
        refresh();
        var candidates = new ArrayList<>(existing);
        for (var row : settings.selectList(new QueryWrapper<SystemSettingEntity>().likeRight("cfg_key", PREFIX))) {
            Snapshot snapshot = decode(row.getCfgValue());
            if (snapshot == null || snapshot.report() == null || snapshot.observedAt() == null) continue;
            RunnerCodexQuotaReport report = snapshot.report();
            Instant at;
            try { at = Instant.parse(snapshot.observedAt()); } catch (Exception ignored) { continue; }
            boolean fresh = !snapshot.failed() && at.isAfter(Instant.now().minusSeconds(900)) && !at.isAfter(Instant.now().plusSeconds(60));
            candidates.add(new CodexQuotaAccountDto(report.accountEmail(), report.accountEmail(), report.plan(), report.credits(), report.limits(), List.of(snapshot.source()), snapshot.observedAt(), fresh, report.resetCredits()));
        }
        var grouped = new LinkedHashMap<String, CodexQuotaAccountDto>();
        for (var account : candidates) {
            String key = account.key().strip().toLowerCase(Locale.ROOT);
            var old = grouped.get(key);
            if (old == null) { grouped.put(key, account); continue; }
            var latest = timestamp(account.lastSyncedAt()).isAfter(timestamp(old.lastSyncedAt())) ? account : old;
            var names = new TreeSet<>(old.runnerNames()); names.addAll(account.runnerNames());
            grouped.put(key, new CodexQuotaAccountDto(key, latest.accountEmail(), latest.plan(), latest.credits(), latest.limits(), List.copyOf(names), latest.lastSyncedAt(), latest.fresh(), latest.resetCredits()));
        }
        if (grouped.isEmpty() && enabled) throw new BadRequestException("尚未读到本机 Codex 额度，请检查 ChatGPT 登录和网络后重试");
        return List.copyOf(grouped.values());
    }
    private Snapshot decode(String value) {
        try { return json.readValue(value, Snapshot.class); } catch (Exception ignored) { return null; }
    }
    private static Instant timestamp(String value) {
        try { return Instant.parse(value); } catch (Exception ignored) {
            try { return LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Shanghai")).toInstant(); }
            catch (Exception invalid) { return Instant.EPOCH; }
        }
    }
}
