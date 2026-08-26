package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import cn.wayuwa.wao.domain.MemoryCollectionDocumentEntity;
import cn.wayuwa.wao.dto.MemoryDocumentDto;
import cn.wayuwa.wao.dto.RunnerMemoryReportItem;
import cn.wayuwa.wao.dto.RunnerMemoryReportRequest;
import cn.wayuwa.wao.mapper.AiRunnerNodeMapper;
import cn.wayuwa.wao.mapper.MemoryCollectionDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 接收已配对 Runner 的有界记忆报告，并幂等地形成机器/应用候选版本。 */
@Service
public class RunnerMemoryCollectionService {
    private final MemoryCollectionDocumentMapper collections;
    private final AiRunnerNodeMapper runners;
    private final UnifiedMemoryService memories;

    public RunnerMemoryCollectionService(MemoryCollectionDocumentMapper collections,
            AiRunnerNodeMapper runners, UnifiedMemoryService memories) {
        this.collections = collections;
        this.runners = runners;
        this.memories = memories;
    }

    /** 保存一次采集结果；同一正文只刷新采集时间，不制造重复候选。 */
    @Transactional
    public List<MemoryDocumentDto> collect(AiRunnerNodeEntity runner, RunnerMemoryReportRequest request) {
        LocalDateTime now = LocalDateTime.now();
        List<RunnerMemoryReportItem> reports = request == null || request.reports() == null
                ? List.of() : request.reports();
        if (reports.isEmpty()) {
            recordFailure(runner.getId(), request == null ? null : request.error(), now);
            return List.of();
        }
        if (reports.size() > 2) throw new BadRequestException("一次最多上传两篇机器/应用记忆报告");
        Set<String> unique = new HashSet<>();
        List<MemoryDocumentDto> changed = new ArrayList<>();
        for (RunnerMemoryReportItem report : reports) {
            if (report == null) throw new BadRequestException("记忆报告不能为空");
            String type = text(report.scopeType()).toLowerCase(Locale.ROOT);
            if (!Set.of("machine", "application").contains(type) || !unique.add(type)) {
                throw new BadRequestException("每次只能各上传一篇机器和应用记忆报告");
            }
            String scopeId = "machine".equals(type) ? runner.getId() : normalizeApplication(runner, report.scopeId());
            String fingerprint = UnifiedMemoryService.contentFingerprint(text(report.category()) + "\n"
                    + text(report.title()) + "\n" + text(report.content()));
            MemoryCollectionDocumentEntity mapping = collections.selectOne(
                    new LambdaQueryWrapper<MemoryCollectionDocumentEntity>()
                            .eq(MemoryCollectionDocumentEntity::getRunnerId, runner.getId())
                            .eq(MemoryCollectionDocumentEntity::getScopeType, type)
                            .eq(MemoryCollectionDocumentEntity::getScopeId, scopeId));
            if (mapping != null && fingerprint.equals(mapping.getContentFingerprint())) {
                mapping.setLastCollectedAt(now); mapping.setUpdatedAt(now); collections.updateById(mapping);
                continue;
            }
            MemoryDocumentDto candidate = memories.createCollectedCandidate(mapping == null ? null : mapping.getMemoryId(),
                    runner.getId(), type, scopeId, report.category(), report.title(), report.content());
            if (mapping == null) {
                mapping = new MemoryCollectionDocumentEntity(); mapping.setId(id()); mapping.setRunnerId(runner.getId());
                mapping.setScopeType(type); mapping.setScopeId(scopeId); mapping.setMemoryId(candidate.id());
                mapping.setCreatedAt(now);
            }
            mapping.setContentFingerprint(fingerprint); mapping.setLastCollectedAt(now); mapping.setUpdatedAt(now);
            if (collections.selectById(mapping.getId()) == null) collections.insert(mapping);
            else collections.updateById(mapping);
            changed.add(candidate);
        }
        runners.update(null, new UpdateWrapper<AiRunnerNodeEntity>().eq("id", runner.getId())
                .set("last_memory_collection_attempt_at", now)
                .set("last_memory_collected_at", now)
                .set("last_memory_collection_error", null));
        return changed;
    }

    private void recordFailure(String runnerId, String error, LocalDateTime now) {
        String message = sanitizeError(error);
        runners.update(null, new UpdateWrapper<AiRunnerNodeEntity>().eq("id", runnerId)
                .set("last_memory_collection_attempt_at", now)
                .set("last_memory_collection_error", message));
    }

    private static String normalizeApplication(AiRunnerNodeEntity runner, String requested) {
        String app = text(requested).toLowerCase(Locale.ROOT);
        if (!app.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw new BadRequestException("应用记忆 id 无效");
        Set<String> engines = new HashSet<>(List.of(
                text(runner.getEngines()).toLowerCase(Locale.ROOT).replace(" ", "").split(",")));
        if (!engines.contains(app)) throw new BadRequestException("执行电脑没有上报该应用能力");
        return app;
    }

    private static String sanitizeError(String value) {
        String message = text(value).strip().replaceAll("(?i)Bearer\\s+[^\\s,;]+", "Bearer [已脱敏]")
                .replaceAll("(?i)(token|secret|password|api[_-]?key)(\\s*[=:]\\s*)[^\\s,;]+", "$1$2[已脱敏]");
        if (message.isEmpty()) message = "本机没有可整理的 Codex 记忆，未创建报告";
        return message.substring(0, Math.min(1000, message.length()));
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
    private static String id() { return "mc" + UUID.randomUUID().toString().replace("-", "").substring(0, 30); }
}
