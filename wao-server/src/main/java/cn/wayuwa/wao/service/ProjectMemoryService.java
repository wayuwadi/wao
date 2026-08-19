package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.AiRunMemoryReadEntity;
import cn.wayuwa.wao.domain.AiRunMemoryWriteEntity;
import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.domain.ProjectMemoryEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.ProjectMemoryCandidate;
import cn.wayuwa.wao.dto.ProjectMemoryContextDto;
import cn.wayuwa.wao.dto.ProjectMemoryDto;
import cn.wayuwa.wao.dto.ProjectMemoryReferenceDto;
import cn.wayuwa.wao.dto.SaveProjectMemoryRequest;
import cn.wayuwa.wao.dto.Step;
import cn.wayuwa.wao.dto.TaskDetail;
import cn.wayuwa.wao.mapper.AiRunMemoryReadMapper;
import cn.wayuwa.wao.mapper.AiRunMemoryWriteMapper;
import cn.wayuwa.wao.mapper.ProjectMemoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 项目级工程记忆的权限、召回、安全过滤、去重和运行引用服务。 */
@Service
public class ProjectMemoryService {
    public static final int MAX_RECALL_COUNT = 5;
    public static final int MAX_RECALL_CHARS = 10_000;
    public static final int MAX_CANDIDATES = 5;
    public static final Set<String> CATEGORIES = Set.of(
            "decision", "convention", "pitfall", "verification", "operation", "context");
    private static final Pattern WORD = Pattern.compile("[\\p{IsHan}]{2,}|[A-Za-z0-9_./-]{2,}");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?is)(-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"
                    + "|\\bBearer\\s+[A-Za-z0-9._~+/=-]{12,}"
                    + "|\\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,})\\b"
                    + "|\\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|passwd|secret)\\s*[=:]\\s*[^\\s,;]{8,})");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final ProjectMemoryMapper memories;
    private final AiRunMemoryReadMapper reads;
    private final AiRunMemoryWriteMapper writes;
    private final ProjectService projects;
    private final ObjectMapper json;

    public ProjectMemoryService(ProjectMemoryMapper memories, AiRunMemoryReadMapper reads,
            AiRunMemoryWriteMapper writes, ProjectService projects, ObjectMapper json) {
        this.memories = memories;
        this.reads = reads;
        this.writes = writes;
        this.projects = projects;
        this.json = json;
    }

    /** 列出当前成员可见项目的工程记忆，可按分类和关键词过滤。 */
    public List<ProjectMemoryDto> list(String projectId, String memberId, String query, String category) {
        projects.get(projectId, memberId);
        String normalizedCategory = normalizeOptionalCategory(category);
        String needle = trim(query).toLowerCase(Locale.ROOT);
        return memories.selectList(new LambdaQueryWrapper<ProjectMemoryEntity>()
                        .eq(ProjectMemoryEntity::getProjectId, projectId)
                        .eq(StringUtils.hasText(normalizedCategory), ProjectMemoryEntity::getCategory,
                                normalizedCategory)
                        .orderByDesc(ProjectMemoryEntity::getUpdatedAt))
                .stream()
                .filter(memory -> !StringUtils.hasText(needle)
                        || (memory.getTitle() + "\n" + memory.getContent()).toLowerCase(Locale.ROOT).contains(needle))
                .map(this::toDto).toList();
    }

    /** 由项目成员人工创建一条工程记忆。 */
    @Transactional
    public ProjectMemoryDto create(String projectId, String memberId, SaveProjectMemoryRequest request) {
        projects.get(projectId, memberId);
        ProjectMemoryEntity entity = build(projectId, request.category(), request.title(), request.content(),
                request.evidenceLinks(), null, null, memberId);
        rejectDuplicate(entity, null);
        memories.insert(entity);
        return toDto(entity);
    }

    /** 修改一条工程记忆；项目权限和项目归属同时校验。 */
    @Transactional
    public ProjectMemoryDto update(String projectId, String memoryId, String memberId,
            SaveProjectMemoryRequest request) {
        projects.get(projectId, memberId);
        ProjectMemoryEntity entity = require(projectId, memoryId);
        ProjectMemoryEntity normalized = build(projectId, request.category(), request.title(), request.content(),
                request.evidenceLinks(), entity.getSourceTaskId(), entity.getSourceRunId(), entity.getCreatedBy());
        rejectDuplicate(normalized, memoryId);
        entity.setCategory(normalized.getCategory());
        entity.setTitle(normalized.getTitle());
        entity.setContent(normalized.getContent());
        entity.setEvidenceLinksJson(normalized.getEvidenceLinksJson());
        entity.setContentFingerprint(normalized.getContentFingerprint());
        entity.setUpdatedAt(LocalDateTime.now());
        memories.updateById(entity);
        return toDto(entity);
    }

    /** 删除一条项目工程记忆；运行引用由数据库外键一并清理。 */
    @Transactional
    public void delete(String projectId, String memoryId, String memberId) {
        projects.get(projectId, memberId);
        require(projectId, memoryId);
        memories.deleteById(memoryId);
    }

    /**
     * 根据任务标题与描述执行有界关键词召回，并固化本次实际读取项。
     *
     * <p>没有关键词命中的记忆不会注入；返回最多五条，标题与正文合计不超过一万字。
     */
    @Transactional
    public List<ProjectMemoryContextDto> recall(AiTaskRunEntity run, TaskEntity task) {
        List<AiRunMemoryReadEntity> existingReads = reads.selectList(
                new LambdaQueryWrapper<AiRunMemoryReadEntity>()
                        .eq(AiRunMemoryReadEntity::getRunId, run.getId())
                        .orderByAsc(AiRunMemoryReadEntity::getRankOrder));
        if (existingReads != null && !existingReads.isEmpty()) {
            Map<String, ProjectMemoryEntity> existingMemories = memories.selectBatchIds(existingReads.stream()
                            .map(AiRunMemoryReadEntity::getMemoryId).toList()).stream()
                    .collect(Collectors.toMap(ProjectMemoryEntity::getId, item -> item));
            return boundedContext(existingReads.stream().map(item -> existingMemories.get(item.getMemoryId()))
                    .filter(java.util.Objects::nonNull).toList());
        }
        String corpus = taskCorpus(task, run.getSubId());
        Set<String> terms = terms(corpus);
        if (terms.isEmpty()) return List.of();
        List<ScoredMemory> ranked = memories.selectList(new LambdaQueryWrapper<ProjectMemoryEntity>()
                        .eq(ProjectMemoryEntity::getProjectId, run.getProjectId())
                        .orderByDesc(ProjectMemoryEntity::getUpdatedAt))
                .stream().map(memory -> new ScoredMemory(memory, score(memory, terms)))
                .filter(item -> item.score() > 0)
                .sorted(Comparator.comparingInt(ScoredMemory::score).reversed()
                        .thenComparing(item -> item.memory().getUpdatedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        List<ProjectMemoryContextDto> result = boundedContext(ranked.stream().map(ScoredMemory::memory).toList());
        LocalDateTime now = LocalDateTime.now();
        for (int index = 0; index < result.size(); index++) {
            AiRunMemoryReadEntity read = new AiRunMemoryReadEntity();
            read.setId(newId("mr"));
            read.setRunId(run.getId());
            read.setMemoryId(result.get(index).id());
            read.setRankOrder(index + 1);
            read.setCreatedAt(now);
            reads.insert(read);
        }
        return result;
    }

    private List<ProjectMemoryContextDto> boundedContext(List<ProjectMemoryEntity> ranked) {
        List<ProjectMemoryContextDto> result = new ArrayList<>();
        int used = 0;
        for (ProjectMemoryEntity memory : ranked) {
            if (result.size() >= MAX_RECALL_COUNT || used >= MAX_RECALL_CHARS) break;
            int remaining = MAX_RECALL_CHARS - used - memory.getTitle().length();
            if (remaining <= 0) break;
            String content = memory.getContent();
            if (content.length() > remaining) {
                if (remaining < 80) continue;
                content = content.substring(0, Math.max(0, remaining - 6)) + "…（已截断）";
            }
            result.add(new ProjectMemoryContextDto(memory.getId(), memory.getCategory(), memory.getTitle(), content,
                    memory.getSourceTaskId(), memory.getSourceRunId(), evidenceLinks(memory)));
            used += memory.getTitle().length() + content.length();
        }
        return result;
    }

    /**
     * 仅为成功运行沉淀经校验的结构化候选；敏感、重复、超量和无效候选不会落库。
     *
     * @return 实际写入的记忆引用
     */
    @Transactional
    public List<ProjectMemoryReferenceDto> persistCandidates(AiTaskRunEntity run,
            List<ProjectMemoryCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        List<ProjectMemoryReferenceDto> created = new ArrayList<>();
        Set<String> fingerprints = new LinkedHashSet<>();
        for (ProjectMemoryCandidate candidate : candidates.stream().limit(MAX_CANDIDATES).toList()) {
            try {
                if (candidate.evidenceLinks() == null || candidate.evidenceLinks().isEmpty()) continue;
                ProjectMemoryEntity entity = build(run.getProjectId(), candidate.category(), candidate.title(),
                        candidate.content(), candidate.evidenceLinks(), run.getTaskId(), run.getId(), "ai");
                if (!fingerprints.add(entity.getContentFingerprint()) || duplicateExists(entity, null)) continue;
                memories.insert(entity);
                AiRunMemoryWriteEntity write = new AiRunMemoryWriteEntity();
                write.setId(newId("mw"));
                write.setRunId(run.getId());
                write.setMemoryId(entity.getId());
                write.setRankOrder(created.size() + 1);
                write.setCreatedAt(LocalDateTime.now());
                writes.insert(write);
                created.add(toReference(entity));
            } catch (BadRequestException | DuplicateKeyException ignored) {
                // 记忆是运行的附加产物；拒绝不安全候选，但绝不能把已成功部署的运行改判失败。
            }
        }
        return created;
    }

    /** 批量读取 AI Run 实际读取的记忆引用，供运行详情展示。 */
    public Map<String, List<ProjectMemoryReferenceDto>> readReferences(List<String> runIds) {
        return references(runIds, true);
    }

    /** 批量读取 AI Run 实际写入的记忆引用，供运行详情展示。 */
    public Map<String, List<ProjectMemoryReferenceDto>> writeReferences(List<String> runIds) {
        return references(runIds, false);
    }

    private Map<String, List<ProjectMemoryReferenceDto>> references(List<String> runIds, boolean reading) {
        if (runIds == null || runIds.isEmpty()) return Map.of();
        List<RunReference> refs;
        if (reading) {
            refs = reads.selectList(new LambdaQueryWrapper<AiRunMemoryReadEntity>()
                            .in(AiRunMemoryReadEntity::getRunId, runIds)
                            .orderByAsc(AiRunMemoryReadEntity::getRankOrder))
                    .stream().map(item -> new RunReference(item.getRunId(), item.getMemoryId(), item.getRankOrder()))
                    .toList();
        } else {
            refs = writes.selectList(new LambdaQueryWrapper<AiRunMemoryWriteEntity>()
                            .in(AiRunMemoryWriteEntity::getRunId, runIds)
                            .orderByAsc(AiRunMemoryWriteEntity::getRankOrder))
                    .stream().map(item -> new RunReference(item.getRunId(), item.getMemoryId(), item.getRankOrder()))
                    .toList();
        }
        if (refs.isEmpty()) return Map.of();
        Map<String, ProjectMemoryEntity> byId = memories.selectBatchIds(refs.stream()
                        .map(RunReference::memoryId).distinct().toList()).stream()
                .collect(Collectors.toMap(ProjectMemoryEntity::getId, item -> item));
        Map<String, List<ProjectMemoryReferenceDto>> result = new LinkedHashMap<>();
        for (RunReference ref : refs) {
            ProjectMemoryEntity memory = byId.get(ref.memoryId());
            if (memory != null) result.computeIfAbsent(ref.runId(), ignored -> new ArrayList<>()).add(toReference(memory));
        }
        return result;
    }

    private ProjectMemoryEntity build(String projectId, String category, String title, String content,
            List<String> links, String taskId, String runId, String createdBy) {
        String safeCategory = normalizeCategory(category);
        String safeTitle = trim(title);
        String safeContent = trim(content);
        if (!StringUtils.hasText(safeTitle) || safeTitle.length() > 200) {
            throw new BadRequestException("项目记忆标题不能为空且不能超过 200 字");
        }
        if (!StringUtils.hasText(safeContent) || safeContent.length() > 5000) {
            throw new BadRequestException("项目记忆正文不能为空且不能超过 5000 字");
        }
        List<String> safeLinks = normalizeLinks(links);
        rejectSensitive(safeTitle + "\n" + safeContent + "\n" + String.join("\n", safeLinks));
        LocalDateTime now = LocalDateTime.now();
        ProjectMemoryEntity entity = new ProjectMemoryEntity();
        entity.setId(newId("pm"));
        entity.setProjectId(projectId);
        entity.setCategory(safeCategory);
        entity.setTitle(safeTitle);
        entity.setContent(safeContent);
        entity.setSourceTaskId(taskId);
        entity.setSourceRunId(runId);
        entity.setEvidenceLinksJson(writeLinks(safeLinks));
        entity.setContentFingerprint(fingerprint(safeContent));
        entity.setCreatedBy(createdBy);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private ProjectMemoryEntity require(String projectId, String memoryId) {
        ProjectMemoryEntity entity = memories.selectById(memoryId);
        if (entity == null || !projectId.equals(entity.getProjectId())) {
            throw new NotFoundException("项目记忆不存在：" + memoryId);
        }
        return entity;
    }

    private void rejectDuplicate(ProjectMemoryEntity entity, String exceptId) {
        if (duplicateExists(entity, exceptId)) throw new BadRequestException("项目中已存在相同内容的工程记忆");
    }

    private boolean duplicateExists(ProjectMemoryEntity entity, String exceptId) {
        return memories.selectCount(new LambdaQueryWrapper<ProjectMemoryEntity>()
                .eq(ProjectMemoryEntity::getProjectId, entity.getProjectId())
                .eq(ProjectMemoryEntity::getContentFingerprint, entity.getContentFingerprint())
                .ne(StringUtils.hasText(exceptId), ProjectMemoryEntity::getId, exceptId)) > 0;
    }

    private static int score(ProjectMemoryEntity memory, Set<String> terms) {
        String title = memory.getTitle().toLowerCase(Locale.ROOT);
        String content = memory.getContent().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (title.contains(term)) score += 4;
            if (content.contains(term)) score += 1;
        }
        return score;
    }

    private String taskCorpus(TaskEntity task, String subId) {
        if (task == null) return "";
        StringBuilder corpus = new StringBuilder(trim(task.getTitle())).append('\n').append(trim(task.getDescription()));
        if (!StringUtils.hasText(task.getDetailJson())) return corpus.toString();
        try {
            TaskDetail detail = json.readValue(task.getDetailJson(), TaskDetail.class);
            List<Step> children = new ArrayList<>();
            if (detail.steps() != null) children.addAll(detail.steps());
            if (detail.subs() != null) children.addAll(detail.subs());
            children.stream()
                    .filter(step -> !step.done())
                    .filter(step -> !StringUtils.hasText(subId) || subId.equals(step.id()))
                    .forEach(step -> corpus.append('\n').append(trim(step.text())).append('\n').append(trim(step.desc())));
        } catch (JsonProcessingException ignored) {
            // 历史异常 JSON 不阻断领取；标题和主描述仍然可以正常召回。
        }
        return corpus.toString();
    }

    private static Set<String> terms(String value) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = WORD.matcher(value == null ? "" : value.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (word.codePoints().allMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
                int[] points = word.codePoints().toArray();
                for (int index = 0; index + 1 < points.length; index++) {
                    result.add(new String(points, index, 2));
                }
            } else {
                result.add(word);
            }
        }
        return result;
    }

    private static String normalizeCategory(String category) {
        String value = trim(category).toLowerCase(Locale.ROOT);
        if (!CATEGORIES.contains(value)) throw new BadRequestException("不支持的项目记忆分类：" + value);
        return value;
    }

    private static String normalizeOptionalCategory(String category) {
        return StringUtils.hasText(category) ? normalizeCategory(category) : "";
    }

    private static List<String> normalizeLinks(List<String> links) {
        if (links == null) return List.of();
        List<String> result = links.stream().map(ProjectMemoryService::trim).filter(StringUtils::hasText)
                .distinct().limit(10).toList();
        if (result.stream().anyMatch(link -> link.length() > 1024 || link.startsWith("//")
                || !(link.startsWith("https://") || link.startsWith("http://") || link.startsWith("/")))) {
            throw new BadRequestException("证据链接必须是 HTTP(S) 或站内绝对路径");
        }
        return result;
    }

    private static void rejectSensitive(String value) {
        if (SENSITIVE.matcher(value).find()) throw new BadRequestException("项目记忆疑似包含凭据或私钥，已拒绝保存");
    }

    private static String fingerprint(String content) {
        String normalized = content.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String writeLinks(List<String> links) {
        try { return json.writeValueAsString(links); }
        catch (JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
    }

    private List<String> evidenceLinks(ProjectMemoryEntity entity) {
        if (!StringUtils.hasText(entity.getEvidenceLinksJson())) return List.of();
        try { return json.readValue(entity.getEvidenceLinksJson(), STRING_LIST); }
        catch (JsonProcessingException ignored) { return List.of(); }
    }

    private ProjectMemoryDto toDto(ProjectMemoryEntity entity) {
        return new ProjectMemoryDto(entity.getId(), entity.getProjectId(), entity.getCategory(), entity.getTitle(),
                entity.getContent(), entity.getSourceTaskId(), entity.getSourceRunId(), evidenceLinks(entity),
                entity.getCreatedBy(), text(entity.getCreatedAt()), text(entity.getUpdatedAt()));
    }

    private ProjectMemoryReferenceDto toReference(ProjectMemoryEntity entity) {
        return new ProjectMemoryReferenceDto(entity.getId(), entity.getProjectId(), entity.getCategory(),
                entity.getTitle(), entity.getContent(), entity.getSourceTaskId(), entity.getSourceRunId(),
                evidenceLinks(entity));
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 32 - prefix.length());
    }
    private static String text(LocalDateTime value) { return value == null ? null : value.toString(); }
    private record ScoredMemory(ProjectMemoryEntity memory, int score) { }
    private record RunReference(String runId, String memoryId, int rank) { }
}
