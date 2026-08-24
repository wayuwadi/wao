package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.domain.MemoryAttachmentEntity;
import cn.wayuwa.wao.domain.MemoryDocumentEntity;
import cn.wayuwa.wao.domain.MemoryOutboxEntity;
import cn.wayuwa.wao.domain.MemoryRevisionEntity;
import cn.wayuwa.wao.domain.MemorySourceEntity;
import cn.wayuwa.wao.domain.MemoryUsageLogEntity;
import cn.wayuwa.wao.domain.OrgEntity;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.domain.ProjectMemoryEntity;
import cn.wayuwa.wao.dto.MemoryDocumentDto;
import cn.wayuwa.wao.dto.MemoryRevisionDto;
import cn.wayuwa.wao.dto.MemorySourceDto;
import cn.wayuwa.wao.dto.SaveMemoryRequest;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.MemoryAttachmentMapper;
import cn.wayuwa.wao.mapper.MemoryDocumentMapper;
import cn.wayuwa.wao.mapper.MemoryOutboxMapper;
import cn.wayuwa.wao.mapper.MemoryRevisionMapper;
import cn.wayuwa.wao.mapper.MemorySourceMapper;
import cn.wayuwa.wao.mapper.MemoryUsageLogMapper;
import cn.wayuwa.wao.mapper.OrgMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import cn.wayuwa.wao.mapper.ProjectMemoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 个人、组织和项目三种范围共用的版本化记忆领域服务。 */
@Service
public class UnifiedMemoryService {
    private static final Logger log = LoggerFactory.getLogger(UnifiedMemoryService.class);
    public static final int MAX_SEARCH_RESULTS = 5;
    public static final int MAX_SEARCH_CHARS = 10_000;
    public static final Set<String> SCOPES = Set.of("personal", "organization", "project");
    private static final Map<String, Set<String>> CATEGORIES = Map.of(
            "personal", Set.of("preference", "responsibility", "plan", "decision", "context", "experience"),
            "organization", Set.of("decision", "policy", "standard", "glossary", "responsibility", "context"),
            "project", Set.of("decision", "convention", "pitfall", "verification", "operation", "context"));
    private static final Pattern WORD = Pattern.compile("[\\p{IsHan}]{2,}|[A-Za-z0-9_./-]{2,}");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?is)(-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"
                    + "|\\bBearer\\s+[A-Za-z0-9._~+/=-]{12,}"
                    + "|\\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,})\\b"
                    + "|\\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|passwd|secret)\\s*[=:]\\s*[^\\s,;]{8,})");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final MemoryDocumentMapper documents;
    private final MemoryRevisionMapper revisions;
    private final MemorySourceMapper sources;
    private final MemoryAttachmentMapper attachments;
    private final MemoryOutboxMapper outbox;
    private final MemoryUsageLogMapper usage;
    private final MemberMapper members;
    private final OrgMapper orgs;
    private final ProjectMapper projects;
    private final ProjectMemoryMapper projectMemories;
    private final ProjectService projectService;
    private final ObjectMapper json;

    public UnifiedMemoryService(MemoryDocumentMapper documents, MemoryRevisionMapper revisions,
            MemorySourceMapper sources, MemoryAttachmentMapper attachments,
            MemoryOutboxMapper outbox, MemoryUsageLogMapper usage,
            MemberMapper members, OrgMapper orgs, ProjectMapper projects,
            ProjectMemoryMapper projectMemories, ProjectService projectService, ObjectMapper json) {
        this.documents = documents;
        this.revisions = revisions;
        this.sources = sources;
        this.attachments = attachments;
        this.outbox = outbox;
        this.usage = usage;
        this.members = members;
        this.orgs = orgs;
        this.projects = projects;
        this.projectMemories = projectMemories;
        this.projectService = projectService;
        this.json = json;
    }

    /** 列出当前成员可访问的记忆，可选择正式、候选或已废止状态。 */
    public List<MemoryDocumentDto> list(String memberId, String scopeType, String scopeId,
            String query, String category, String state) {
        Scope scope = scope(memberId, scopeType, scopeId);
        validateOptionalCategory(scope.type(), category);
        String normalizedState = normalizeState(state);
        String needle = text(query).trim().toLowerCase(Locale.ROOT);
        List<MemoryDocumentEntity> rows = documents.selectList(new LambdaQueryWrapper<MemoryDocumentEntity>()
                .eq(MemoryDocumentEntity::getScopeType, scope.type())
                .eq(MemoryDocumentEntity::getScopeId, scope.id())
                .eq(StringUtils.hasText(category), MemoryDocumentEntity::getCategory, category)
                .eq(StringUtils.hasText(normalizedState), MemoryDocumentEntity::getState, normalizedState)
                .orderByDesc(MemoryDocumentEntity::getUpdatedAt)
                .last("LIMIT 300"));
        List<MemoryDocumentDto> result = new ArrayList<>();
        for (MemoryDocumentEntity row : rows) {
            MemoryRevisionEntity revision = displayRevision(row, normalizedState);
            if (revision == null) continue;
            if (StringUtils.hasText(needle)
                    && !(revision.getTitle() + "\n" + revision.getContentMd()).toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            result.add(dto(row, revision));
        }
        return result;
    }

    /** 读取一篇记忆；无权访问时按不存在处理。 */
    public MemoryDocumentDto get(String memberId, String memoryId, String revisionId) {
        MemoryDocumentEntity document = requireDocument(memoryId);
        scope(memberId, document.getScopeType(), document.getScopeId());
        MemoryRevisionEntity revision = StringUtils.hasText(revisionId)
                ? requireRevision(document.getId(), revisionId) : displayRevision(document, "");
        if (revision == null) throw new NotFoundException("记忆版本不存在");
        return dto(document, revision);
    }

    /** 按版本倒序返回完整历史。 */
    public List<MemoryRevisionDto> revisions(String memberId, String memoryId) {
        MemoryDocumentEntity document = requireDocument(memoryId);
        scope(memberId, document.getScopeType(), document.getScopeId());
        return revisions.selectList(new LambdaQueryWrapper<MemoryRevisionEntity>()
                        .eq(MemoryRevisionEntity::getMemoryId, memoryId)
                        .orderByDesc(MemoryRevisionEntity::getVersionNo))
                .stream().map(this::revisionDto).toList();
    }

    /** 新建记忆：个人域立即发布，组织和项目域形成待审核草稿。 */
    @Transactional
    public MemoryDocumentDto create(String memberId, SaveMemoryRequest request, String authorType) {
        Scope scope = scope(memberId, request.scopeType(), request.scopeId());
        Normalized input = normalize(scope.type(), request);
        LocalDateTime now = LocalDateTime.now();
        MemoryDocumentEntity document = new MemoryDocumentEntity();
        document.setId(newId("md"));
        document.setScopeType(scope.type());
        document.setScopeId(scope.id());
        document.setCategory(input.category());
        document.setState("personal".equals(scope.type()) ? "active" : "draft");
        document.setCreatedBy(memberId);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        documents.insert(document);
        MemoryRevisionEntity revision = buildRevision(document, 1, null, input, memberId, authorType, now);
        if ("personal".equals(scope.type())) publishRevision(document, revision, memberId, now);
        else revisions.insert(revision);
        insertSources(revision, input.evidenceLinks(), now);
        event("memory.revision.created", document.getId(), revision.getId(), now);
        return dto(document, revision);
    }

    /** AI Run 的受控内部入口：项目候选只形成草稿，并绑定真实 Task/Run 来源。 */
    @Transactional
    public MemoryDocumentDto createProjectCandidate(String projectId, String category, String title,
            String content, List<String> evidenceLinks, String taskId, String runId) {
        if (projects.selectById(projectId) == null) throw new NotFoundException("项目不存在：" + projectId);
        SaveMemoryRequest request = new SaveMemoryRequest("project", projectId, category, title, content,
                "AI Run 完成后生成的待审核工程记忆", evidenceLinks, null, null);
        Normalized input = normalize("project", request);
        LocalDateTime now = LocalDateTime.now();
        MemoryDocumentEntity document = new MemoryDocumentEntity();
        document.setId(newId("md")); document.setScopeType("project"); document.setScopeId(projectId);
        document.setCategory(input.category()); document.setState("draft"); document.setCreatedBy("ai");
        document.setCreatedAt(now); document.setUpdatedAt(now); documents.insert(document);
        MemoryRevisionEntity revision = buildRevision(document, 1, null, input, "ai", "ai", now);
        revisions.insert(revision); insertSources(revision, input.evidenceLinks(), now);
        insertInternalSource(revision.getId(), "task", taskId, now);
        insertInternalSource(revision.getId(), "run", runId, now);
        event("memory.project.candidate.created", document.getId(), revision.getId(), now);
        return dto(document, revision);
    }

    /** 基于当前发布版本创建新修订；过期 baseRevisionId 会得到 409。 */
    @Transactional
    public MemoryDocumentDto revise(String memberId, String memoryId, SaveMemoryRequest request, String authorType) {
        MemoryDocumentEntity document = requireDocument(memoryId);
        scope(memberId, document.getScopeType(), document.getScopeId());
        if (!document.getScopeType().equals(normalizeScope(request.scopeType()))) {
            throw new BadRequestException("修订不能改变记忆范围");
        }
        String expected = document.getCurrentPublishedRevisionId();
        if (!java.util.Objects.equals(expected, emptyToNull(request.baseRevisionId()))) {
            throw new ConflictException("记忆已在其他设备更新，请比较最新版本后再保存");
        }
        Normalized input = normalize(document.getScopeType(), request);
        int next = nextVersion(memoryId);
        LocalDateTime now = LocalDateTime.now();
        MemoryRevisionEntity revision = buildRevision(document, next, expected, input, memberId, authorType, now);
        document.setCategory(input.category());
        document.setUpdatedAt(now);
        if ("personal".equals(document.getScopeType())) publishRevision(document, revision, memberId, now);
        else {
            revision.setState("draft");
            revisions.insert(revision);
            document.setState("draft");
            documents.updateById(document);
        }
        insertSources(revision, input.evidenceLinks(), now);
        event("memory.revision.created", document.getId(), revision.getId(), now);
        return dto(document, revision);
    }

    /** 将候选发布为当前正式版本；只允许现有组织管理员或项目负责人。 */
    @Transactional
    public MemoryDocumentDto publish(String memberId, String revisionId) {
        MemoryRevisionEntity revision = revisions.selectById(revisionId);
        if (revision == null || !"draft".equals(revision.getState())) throw new NotFoundException("待发布版本不存在");
        MemoryDocumentEntity document = requireDocument(revision.getMemoryId());
        requirePublisher(memberId, document);
        if (!java.util.Objects.equals(document.getCurrentPublishedRevisionId(), revision.getBaseRevisionId())) {
            throw new ConflictException("候选基于的正式版本已经变化，请重新生成修订");
        }
        LocalDateTime now = LocalDateTime.now();
        if (StringUtils.hasText(document.getCurrentPublishedRevisionId())) {
            MemoryRevisionEntity old = revisions.selectById(document.getCurrentPublishedRevisionId());
            if (old != null) { old.setState("superseded"); revisions.updateById(old); }
        }
        revision.setState("published");
        revision.setPublishedBy(memberId);
        revision.setPublishedAt(now);
        revisions.updateById(revision);
        document.setCurrentPublishedRevisionId(revision.getId());
        document.setState("active");
        document.setUpdatedAt(now);
        updatePublishedPointer(document, revision.getBaseRevisionId());
        if ("project".equals(document.getScopeType())) syncPublishedProject(document, revision, memberId, now);
        event("memory.revision.published", document.getId(), revision.getId(), now);
        return dto(document, revision);
    }

    /** 驳回候选但保留审计记录。 */
    @Transactional
    public MemoryDocumentDto reject(String memberId, String revisionId) {
        MemoryRevisionEntity revision = revisions.selectById(revisionId);
        if (revision == null || !"draft".equals(revision.getState())) throw new NotFoundException("待审核版本不存在");
        MemoryDocumentEntity document = requireDocument(revision.getMemoryId());
        requirePublisher(memberId, document);
        revision.setState("rejected");
        revisions.updateById(revision);
        document.setState(StringUtils.hasText(document.getCurrentPublishedRevisionId()) ? "active" : "rejected");
        document.setUpdatedAt(LocalDateTime.now());
        documents.updateById(document);
        event("memory.revision.rejected", document.getId(), revision.getId(), LocalDateTime.now());
        return dto(document, revision);
    }

    /** 废止记忆；历史版本仍可追溯，但不再注入 AI。 */
    @Transactional
    public void archive(String memberId, String memoryId) {
        MemoryDocumentEntity document = requireDocument(memoryId);
        if ("personal".equals(document.getScopeType())) scope(memberId, "personal", document.getScopeId());
        else requirePublisher(memberId, document);
        LocalDateTime now = LocalDateTime.now();
        document.setState("archived");
        document.setArchivedAt(now);
        document.setUpdatedAt(now);
        documents.updateById(document);
        if ("project".equals(document.getScopeType())) projectMemories.deleteById(document.getId());
        event("memory.archived", document.getId(), document.getCurrentPublishedRevisionId(), now);
    }

    /** 兼容个人旧入口：以旧 id 同步成新的正式版本，并可带入真实附件。 */
    @Transactional
    public void mirrorPersonal(String memoryId, String memberId, String category, String title, String content,
            String authorType, String fileName, String contentType, Long byteSize, byte[] fileContent) {
        mirrorPublished(memoryId, "personal", memberId, category, title, content, authorType,
                memberId, List.of(), fileName, contentType, byteSize, fileContent);
    }

    /** 兼容已发布的历史项目记录；只有审核发布路径应在新功能中调用。 */
    @Transactional
    public void mirrorPublishedProject(String memoryId, String projectId, String category, String title,
            String content, String createdBy, List<String> evidenceLinks) {
        mirrorPublished(memoryId, "project", projectId, category, title, content,
                "ai".equals(createdBy) ? "ai" : "human", createdBy, evidenceLinks,
                null, null, null, null);
    }

    private void mirrorPublished(String memoryId, String scopeType, String scopeId, String category,
            String title, String content, String authorType, String createdBy, List<String> evidenceLinks,
            String fileName, String contentType, Long byteSize, byte[] fileContent) {
        SaveMemoryRequest request = new SaveMemoryRequest(scopeType, scopeId, category, title,
                StringUtils.hasText(content) ? content : "（仅保存附件原件）", "从兼容入口同步",
                evidenceLinks, null, null);
        Normalized input = normalize(scopeType, request);
        LocalDateTime now = LocalDateTime.now();
        MemoryDocumentEntity document = documents.selectById(memoryId);
        if (document == null) {
            document = new MemoryDocumentEntity(); document.setId(memoryId); document.setScopeType(scopeType);
            document.setScopeId(scopeId); document.setCategory(category); document.setState("active");
            document.setCreatedBy(createdBy); document.setCreatedAt(now); document.setUpdatedAt(now);
            documents.insert(document);
        }
        MemoryRevisionEntity revision = buildRevision(document, nextVersion(memoryId),
                document.getCurrentPublishedRevisionId(), input, createdBy, authorType, now);
        publishRevision(document, revision, createdBy, now);
        insertSources(revision, input.evidenceLinks(), now);
        if (fileContent != null && StringUtils.hasText(fileName)) {
            MemoryAttachmentEntity attachment = new MemoryAttachmentEntity();
            attachment.setId(newId("ma")); attachment.setRevisionId(revision.getId());
            attachment.setFileName(fileName); attachment.setContentType(StringUtils.hasText(contentType)
                    ? contentType : "application/octet-stream");
            attachment.setByteSize(byteSize == null ? (long) fileContent.length : byteSize);
            attachment.setFileContent(fileContent); attachment.setCreatedAt(now); attachments.insert(attachment);
        }
        if ("project".equals(scopeType)) syncPublishedProject(document, revision, createdBy, now);
        event("memory.compatibility.mirrored", document.getId(), revision.getId(), now);
    }

    private void syncPublishedProject(MemoryDocumentEntity document, MemoryRevisionEntity revision,
            String publisher, LocalDateTime now) {
        ProjectMemoryEntity legacy = projectMemories.selectById(document.getId());
        if (legacy == null) {
            legacy = new ProjectMemoryEntity(); legacy.setId(document.getId()); legacy.setProjectId(document.getScopeId());
            legacy.setCreatedBy(publisher); legacy.setCreatedAt(now);
        }
        legacy.setCategory(document.getCategory()); legacy.setTitle(revision.getTitle());
        legacy.setContent(revision.getContentMd()); legacy.setContentFingerprint(revision.getContentFingerprint());
        legacy.setEvidenceLinksJson(revision.getEvidenceLinksJson()); legacy.setUpdatedAt(now);
        if (projectMemories.selectById(legacy.getId()) == null) projectMemories.insert(legacy);
        else projectMemories.updateById(legacy);
    }

    /** 搜索当前范围的正式且未过期记忆，供 MCP 和 Runner 使用。 */
    public List<MemoryDocumentDto> searchPublished(String memberId, String scopeType, String scopeId, String query) {
        Scope scope = scope(memberId, scopeType, scopeId);
        Set<String> terms = terms(query);
        if (terms.isEmpty()) return List.of();
        LocalDateTime now = LocalDateTime.now();
        List<Scored> ranked = new ArrayList<>();
        List<MemoryDocumentEntity> candidates = documents.selectList(new LambdaQueryWrapper<MemoryDocumentEntity>()
                .eq(MemoryDocumentEntity::getScopeType, scope.type())
                .eq(MemoryDocumentEntity::getScopeId, scope.id())
                .isNotNull(MemoryDocumentEntity::getCurrentPublishedRevisionId)
                .ne(MemoryDocumentEntity::getState, "archived")
                .orderByDesc(MemoryDocumentEntity::getUpdatedAt).last("LIMIT 10000"));
        if (candidates.isEmpty()) return List.of();
        Map<String, MemoryRevisionEntity> published = revisions.selectBatchIds(candidates.stream()
                        .map(MemoryDocumentEntity::getCurrentPublishedRevisionId).distinct().toList())
                .stream().collect(Collectors.toMap(MemoryRevisionEntity::getId, Function.identity()));
        for (MemoryDocumentEntity document : candidates) {
            MemoryRevisionEntity revision = published.get(document.getCurrentPublishedRevisionId());
            if (revision == null || revision.getExpiresAt() != null && !revision.getExpiresAt().isAfter(now)) continue;
            int score = score(revision, terms);
            if (score > 0) ranked.add(new Scored(document, revision, score));
        }
        ranked.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(item -> item.document().getUpdatedAt(), Comparator.reverseOrder()));
        List<MemoryDocumentDto> result = new ArrayList<>();
        int chars = 0;
        for (Scored item : ranked) {
            if (result.size() >= MAX_SEARCH_RESULTS || chars >= MAX_SEARCH_CHARS) break;
            MemoryRevisionEntity revision = item.revision();
            int remaining = MAX_SEARCH_CHARS - chars - revision.getTitle().length();
            if (remaining <= 0) break;
            if (revision.getContentMd().length() > remaining) {
                MemoryRevisionEntity clipped = copy(revision);
                clipped.setContentMd(revision.getContentMd().substring(0, Math.max(0, remaining - 6)) + "…（已截断）");
                revision = clipped;
            }
            result.add(dto(item.document(), revision));
            chars += revision.getTitle().length() + revision.getContentMd().length();
        }
        return result;
    }

    /** 记录不含正文的 MCP/Runner 使用数据，用于真实效率基线。 */
    public void audit(String tokenId, String memberId, String client, String scopeType, String scopeId,
            String action, boolean success, int resultCount, long startedNanos, String errorCode) {
        try {
            MemoryUsageLogEntity row = new MemoryUsageLogEntity();
            row.setId(newId("mu")); row.setTokenId(tokenId); row.setMemberId(memberId); row.setClientType(client);
            row.setScopeType(scopeType); row.setScopeId(scopeId); row.setActionName(action); row.setSuccess(success);
            row.setResultCount(resultCount); row.setLatencyMs(Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
            row.setErrorCode(errorCode); row.setCreatedAt(LocalDateTime.now()); usage.insert(row);
        } catch (RuntimeException exception) {
            // 审计是辅助数据；失败不能把已经成功提交的权威正文伪装成失败并诱发客户端重复写入。
            log.warn("Unified memory usage audit failed: scope={}, action={}", scopeType, action, exception);
        }
    }

    /** 验证一条 outbox 的聚合指针后标记完成；失败留给下一轮重试。 */
    @Transactional
    public void processOutbox(MemoryOutboxEntity row) {
        MemoryDocumentEntity document = documents.selectById(row.getAggregateId());
        row.setAttemptCount((row.getAttemptCount() == null ? 0 : row.getAttemptCount()) + 1);
        if (document == null) row.setLastError("记忆文档不存在");
        else { row.setProcessedAt(LocalDateTime.now()); row.setLastError(null); }
        outbox.updateById(row);
    }

    /** 返回等待派生处理的事件，限制批量避免多节点长期占用数据库。 */
    public List<MemoryOutboxEntity> pendingOutbox() {
        return outbox.selectList(new LambdaQueryWrapper<MemoryOutboxEntity>()
                .isNull(MemoryOutboxEntity::getProcessedAt)
                .orderByAsc(MemoryOutboxEntity::getCreatedAt).last("LIMIT 100"));
    }

    /** 每日核对正式版本指针；发现损坏时保留事件错误供管理员定位，不伪造修复正文。 */
    public int verifyPublishedPointers() {
        int invalid = 0;
        for (MemoryDocumentEntity document : documents.selectList(new LambdaQueryWrapper<MemoryDocumentEntity>()
                .isNotNull(MemoryDocumentEntity::getCurrentPublishedRevisionId))) {
            MemoryRevisionEntity revision = revisions.selectById(document.getCurrentPublishedRevisionId());
            if (revision == null || !document.getId().equals(revision.getMemoryId())) invalid++;
        }
        return invalid;
    }

    public Set<String> categories(String scopeType) {
        String scope = normalizeScope(scopeType);
        return CATEGORIES.get(scope);
    }

    /** 按来源 Run 返回 AI 生成的项目候选或其后续发布版本，供运行详情追溯。 */
    public Map<String, List<MemoryDocumentDto>> projectMemoriesByRuns(List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) return Map.of();
        List<MemorySourceEntity> runSources = sources.selectList(new LambdaQueryWrapper<MemorySourceEntity>()
                .eq(MemorySourceEntity::getSourceType, "run")
                .in(MemorySourceEntity::getSourceId, runIds)
                .orderByAsc(MemorySourceEntity::getCreatedAt));
        if (runSources.isEmpty()) return Map.of();
        Map<String, MemoryRevisionEntity> byRevision = revisions.selectBatchIds(runSources.stream()
                        .map(MemorySourceEntity::getRevisionId).distinct().toList())
                .stream().collect(Collectors.toMap(MemoryRevisionEntity::getId, Function.identity()));
        Map<String, MemoryDocumentEntity> byDocument = documents.selectBatchIds(byRevision.values().stream()
                        .map(MemoryRevisionEntity::getMemoryId).distinct().toList())
                .stream().filter(item -> "project".equals(item.getScopeType()))
                .collect(Collectors.toMap(MemoryDocumentEntity::getId, Function.identity()));
        Map<String, List<MemoryDocumentDto>> result = new java.util.LinkedHashMap<>();
        for (MemorySourceEntity source : runSources) {
            MemoryRevisionEntity revision = byRevision.get(source.getRevisionId());
            MemoryDocumentEntity document = revision == null ? null : byDocument.get(revision.getMemoryId());
            if (document != null) result.computeIfAbsent(source.getSourceId(), ignored -> new ArrayList<>())
                    .add(dto(document, revision));
        }
        return result;
    }

    private void publishRevision(MemoryDocumentEntity document, MemoryRevisionEntity revision,
            String publisher, LocalDateTime now) {
        if (StringUtils.hasText(document.getCurrentPublishedRevisionId())) {
            MemoryRevisionEntity old = revisions.selectById(document.getCurrentPublishedRevisionId());
            if (old != null) { old.setState("superseded"); revisions.updateById(old); }
        }
        revision.setState("published"); revision.setPublishedBy(publisher); revision.setPublishedAt(now);
        revisions.insert(revision);
        document.setCurrentPublishedRevisionId(revision.getId()); document.setState("active");
        document.setUpdatedAt(now); updatePublishedPointer(document, revision.getBaseRevisionId());
    }

    /** 数据库条件更新使两台设备同时基于同一正式版本提交时只有一个能前进指针。 */
    private void updatePublishedPointer(MemoryDocumentEntity document, String expectedRevisionId) {
        LambdaUpdateWrapper<MemoryDocumentEntity> condition = new LambdaUpdateWrapper<MemoryDocumentEntity>()
                .eq(MemoryDocumentEntity::getId, document.getId());
        if (StringUtils.hasText(expectedRevisionId)) {
            condition.eq(MemoryDocumentEntity::getCurrentPublishedRevisionId, expectedRevisionId);
        } else {
            condition.isNull(MemoryDocumentEntity::getCurrentPublishedRevisionId);
        }
        if (documents.update(document, condition) != 1) {
            throw new ConflictException("记忆已在其他设备更新，请比较最新版本后再保存");
        }
    }

    private MemoryRevisionEntity buildRevision(MemoryDocumentEntity document, int version,
            String baseRevisionId, Normalized input, String memberId, String authorType, LocalDateTime now) {
        MemoryRevisionEntity revision = new MemoryRevisionEntity();
        revision.setId(newId("mr")); revision.setMemoryId(document.getId()); revision.setVersionNo(version);
        revision.setState("draft"); revision.setTitle(input.title()); revision.setContentMd(input.content());
        revision.setChangeSummary(input.changeSummary()); revision.setContentFingerprint(fingerprint(input.content()));
        revision.setBaseRevisionId(baseRevisionId); revision.setEvidenceLinksJson(writeLinks(input.evidenceLinks()));
        revision.setAuthorType("ai".equals(authorType) ? "ai" : "human"); revision.setCreatedBy(memberId);
        revision.setCreatedAt(now); revision.setExpiresAt(input.expiresAt());
        return revision;
    }

    private void insertSources(MemoryRevisionEntity revision, List<String> links, LocalDateTime now) {
        for (String link : links) {
            MemorySourceEntity source = new MemorySourceEntity();
            source.setId(newId("ms")); source.setRevisionId(revision.getId()); source.setSourceType(sourceType(link));
            source.setSourceUrl(link); source.setExcerptHash(null); source.setCreatedAt(now); sources.insert(source);
        }
    }

    private void insertInternalSource(String revisionId, String type, String sourceId, LocalDateTime now) {
        if (!StringUtils.hasText(sourceId)) return;
        MemorySourceEntity source = new MemorySourceEntity(); source.setId(newId("ms"));
        source.setRevisionId(revisionId); source.setSourceType(type); source.setSourceId(sourceId);
        source.setCreatedAt(now); sources.insert(source);
    }

    private void event(String type, String memoryId, String revisionId, LocalDateTime now) {
        MemoryOutboxEntity row = new MemoryOutboxEntity();
        row.setId(newId("mo")); row.setEventType(type); row.setAggregateId(memoryId); row.setRevisionId(revisionId);
        row.setCreatedAt(now); row.setAttemptCount(0); outbox.insert(row);
    }

    private Scope scope(String memberId, String requestedType, String requestedId) {
        if (!StringUtils.hasText(memberId)) throw new NotFoundException("记忆范围不存在");
        String type = normalizeScope(requestedType);
        if ("personal".equals(type)) {
            if (StringUtils.hasText(requestedId) && !memberId.equals(requestedId)) throw new NotFoundException("记忆范围不存在");
            return new Scope(type, memberId);
        }
        if ("organization".equals(type)) {
            OrgEntity org = orgs.selectList(null).stream().findFirst().orElseThrow(() -> new NotFoundException("组织不存在"));
            if (StringUtils.hasText(requestedId) && !org.getId().equals(requestedId)) throw new NotFoundException("记忆范围不存在");
            if (members.selectById(memberId) == null) throw new NotFoundException("记忆范围不存在");
            return new Scope(type, org.getId());
        }
        if (!StringUtils.hasText(requestedId)) throw new BadRequestException("项目记忆必须指定项目");
        projectService.requireAccess(requestedId, memberId);
        return new Scope(type, requestedId);
    }

    private void requirePublisher(String memberId, MemoryDocumentEntity document) {
        scope(memberId, document.getScopeType(), document.getScopeId());
        MemberEntity member = members.selectById(memberId);
        boolean admin = member != null && ("管理员".equals(member.getRole()) || "超级管理员".equals(member.getRole()));
        if ("organization".equals(document.getScopeType()) && admin) return;
        if ("project".equals(document.getScopeType())) {
            ProjectEntity project = projects.selectById(document.getScopeId());
            if (admin || project != null && memberId.equals(project.getOwnerId())) return;
        }
        throw new NotFoundException("待审核记忆不存在");
    }

    private MemoryDocumentEntity requireDocument(String id) {
        MemoryDocumentEntity document = documents.selectById(id);
        if (document == null) throw new NotFoundException("记忆不存在");
        return document;
    }

    private MemoryRevisionEntity requireRevision(String memoryId, String revisionId) {
        MemoryRevisionEntity revision = revisions.selectById(revisionId);
        if (revision == null || !memoryId.equals(revision.getMemoryId())) throw new NotFoundException("记忆版本不存在");
        return revision;
    }

    private MemoryRevisionEntity displayRevision(MemoryDocumentEntity document, String state) {
        if ("draft".equals(state) || "rejected".equals(state)) {
            return revisions.selectList(new LambdaQueryWrapper<MemoryRevisionEntity>()
                            .eq(MemoryRevisionEntity::getMemoryId, document.getId())
                            .eq(MemoryRevisionEntity::getState, state)
                            .orderByDesc(MemoryRevisionEntity::getVersionNo).last("LIMIT 1"))
                    .stream().findFirst().orElse(null);
        }
        if (StringUtils.hasText(document.getCurrentPublishedRevisionId())) {
            return revisions.selectById(document.getCurrentPublishedRevisionId());
        }
        return revisions.selectList(new LambdaQueryWrapper<MemoryRevisionEntity>()
                        .eq(MemoryRevisionEntity::getMemoryId, document.getId())
                        .orderByDesc(MemoryRevisionEntity::getVersionNo).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    private int nextVersion(String memoryId) {
        return revisions.selectList(new LambdaQueryWrapper<MemoryRevisionEntity>()
                        .eq(MemoryRevisionEntity::getMemoryId, memoryId)
                        .orderByDesc(MemoryRevisionEntity::getVersionNo).last("LIMIT 1"))
                .stream().findFirst().map(row -> row.getVersionNo() + 1).orElse(1);
    }

    private MemoryDocumentDto dto(MemoryDocumentEntity document, MemoryRevisionEntity revision) {
        return new MemoryDocumentDto(document.getId(), document.getScopeType(), document.getScopeId(),
                document.getCategory(), document.getState(), document.getCurrentPublishedRevisionId(),
                revisionDto(revision), document.getCreatedBy(), document.getCreatedAt(),
                document.getUpdatedAt(), document.getArchivedAt());
    }

    private MemoryRevisionDto revisionDto(MemoryRevisionEntity revision) {
        List<MemorySourceDto> sourceDtos = sources.selectList(new LambdaQueryWrapper<MemorySourceEntity>()
                        .eq(MemorySourceEntity::getRevisionId, revision.getId())
                        .orderByAsc(MemorySourceEntity::getCreatedAt))
                .stream().map(row -> new MemorySourceDto(row.getSourceType(), row.getSourceId(),
                        row.getSourceUrl(), row.getExcerptHash())).toList();
        return new MemoryRevisionDto(revision.getId(), revision.getVersionNo(), revision.getState(),
                revision.getTitle(), revision.getContentMd(), revision.getChangeSummary(), revision.getBaseRevisionId(),
                revision.getAuthorType(), revision.getCreatedBy(), revision.getCreatedAt(), revision.getPublishedBy(),
                revision.getPublishedAt(), revision.getExpiresAt(), sourceDtos);
    }

    private Normalized normalize(String scopeType, SaveMemoryRequest request) {
        String category = text(request.category()).trim().toLowerCase(Locale.ROOT);
        validateCategory(scopeType, category);
        String title = text(request.title()).trim();
        String content = text(request.content()).trim();
        if (title.isEmpty() || title.length() > 200) throw new BadRequestException("记忆标题不能为空且不能超过 200 字");
        if (content.isEmpty() || content.length() > 20_000) throw new BadRequestException("记忆正文不能为空且不能超过 20000 字");
        String summary = text(request.changeSummary()).trim();
        if (summary.length() > 1000) throw new BadRequestException("变更说明不能超过 1000 字");
        List<String> links = normalizeLinks(request.evidenceLinks());
        rejectSensitive(title + "\n" + content + "\n" + String.join("\n", links));
        return new Normalized(category, title, content, summary, links, request.expiresAt());
    }

    private static List<String> normalizeLinks(List<String> links) {
        if (links == null) return List.of();
        List<String> result = links.stream().map(UnifiedMemoryService::text).map(String::trim)
                .filter(StringUtils::hasText).distinct().limit(10).toList();
        if (result.stream().anyMatch(link -> link.length() > 1024 || link.startsWith("//")
                || !(link.startsWith("https://") || link.startsWith("http://") || link.startsWith("/")))) {
            throw new BadRequestException("证据链接必须是 HTTP(S) 或站内绝对路径");
        }
        return result;
    }

    private static String normalizeScope(String scope) {
        String value = text(scope).trim().toLowerCase(Locale.ROOT);
        if (!SCOPES.contains(value)) throw new BadRequestException("不支持的记忆范围：" + value);
        return value;
    }

    private static String normalizeState(String state) {
        String value = text(state).trim().toLowerCase(Locale.ROOT);
        if (!value.isEmpty() && !Set.of("active", "draft", "rejected", "archived").contains(value)) {
            throw new BadRequestException("不支持的记忆状态：" + value);
        }
        return value;
    }

    private static void validateCategory(String scope, String category) {
        if (!CATEGORIES.getOrDefault(scope, Set.of()).contains(category)) {
            throw new BadRequestException("不支持的" + scope + "记忆分类：" + category);
        }
    }

    private static void validateOptionalCategory(String scope, String category) {
        if (StringUtils.hasText(category)) validateCategory(scope, category.trim().toLowerCase(Locale.ROOT));
    }

    private static void rejectSensitive(String value) {
        if (SENSITIVE.matcher(value).find()) throw new BadRequestException("记忆疑似包含凭据或私钥，已拒绝保存");
    }

    private static Set<String> terms(String value) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = WORD.matcher(text(value).toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (word.codePoints().allMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
                int[] points = word.codePoints().toArray();
                for (int index = 0; index + 1 < points.length; index++) result.add(new String(points, index, 2));
            } else result.add(word);
        }
        return result;
    }

    private static int score(MemoryRevisionEntity revision, Set<String> terms) {
        String title = revision.getTitle().toLowerCase(Locale.ROOT);
        String content = revision.getContentMd().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) { if (title.contains(term)) score += 4; if (content.contains(term)) score += 1; }
        return score;
    }

    private static String sourceType(String link) {
        if (link.contains("/tasks/")) return "task";
        if (link.contains("/ai/runs/") || link.contains("/runs/")) return "run";
        if (link.contains("/docs/")) return "doc";
        return "url";
    }

    private String writeLinks(List<String> links) {
        try { return json.writeValueAsString(links); }
        catch (JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String fingerprint(String value) {
        try {
            String normalized = text(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static MemoryRevisionEntity copy(MemoryRevisionEntity source) {
        MemoryRevisionEntity target = new MemoryRevisionEntity();
        target.setId(source.getId()); target.setMemoryId(source.getMemoryId()); target.setVersionNo(source.getVersionNo());
        target.setState(source.getState()); target.setTitle(source.getTitle()); target.setContentMd(source.getContentMd());
        target.setChangeSummary(source.getChangeSummary()); target.setBaseRevisionId(source.getBaseRevisionId());
        target.setAuthorType(source.getAuthorType()); target.setCreatedBy(source.getCreatedBy());
        target.setCreatedAt(source.getCreatedAt()); target.setPublishedBy(source.getPublishedBy());
        target.setPublishedAt(source.getPublishedAt()); target.setExpiresAt(source.getExpiresAt());
        return target;
    }

    /** 保持统一模型 id 可直接映射到仍为 VARCHAR(32) 的旧兼容表。 */
    private static String newId(String prefix) {
        String random = UUID.randomUUID().toString().replace("-", "");
        return prefix + random.substring(0, 32 - prefix.length());
    }
    private static String text(String value) { return value == null ? "" : value; }
    private static String emptyToNull(String value) { return StringUtils.hasText(value) ? value : null; }
    private record Scope(String type, String id) { }
    private record Normalized(String category, String title, String content, String changeSummary,
                              List<String> evidenceLinks, LocalDateTime expiresAt) { }
    private record Scored(MemoryDocumentEntity document, MemoryRevisionEntity revision, int score) { }
}
