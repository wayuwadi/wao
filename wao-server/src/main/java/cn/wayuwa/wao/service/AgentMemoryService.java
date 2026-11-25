package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.MemoryDocumentEntity;
import cn.wayuwa.wao.dto.SaveMemoryRequest;
import cn.wayuwa.wao.mapper.MemoryDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Member-owned memories shared by the member's explicitly authorized Agent clients. */
@Service
public class AgentMemoryService {
    private static final Set<String> KINDS = Set.of("preference", "experience");
    private static final Set<String> SOURCES = Set.of("codex", "agy", "import", "manual");
    private static final Set<String> STATES = Set.of("draft", "active", "rejected", "archived");
    private static final Pattern PROJECT_KEY = Pattern.compile("[a-z0-9][a-z0-9._-]*(/[a-z0-9][a-z0-9._-]*)*");
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(wao_(?:workspace|memory)_[a-z0-9_-]{16,}|(?:https?://)[^\\s/@:]+:[^\\s/@]+@"
            + "|(?:password|passwd|secret|token|api[ _-]?key|密码|口令|令牌)\\s*(?:[=:：]|是)\\s*[^\\s,，;；]{4,})");
    private static final String SELECT = """
            SELECT d.id, d.category, d.state, d.updated_at, r.id AS revision_id, r.version_no, r.title,
                   r.content_md, r.change_summary, r.created_at, a.project_key, a.source_agent, a.startup
            FROM memory_document d
            JOIN memory_revision r ON r.memory_id = d.id
            JOIN agent_memory_revision a ON a.revision_id = r.id
            WHERE d.scope_type = 'agent' AND d.scope_id = ?
            """;
    private static final String CURRENT = """
            AND r.version_no = (SELECT MAX(latest.version_no) FROM memory_revision latest WHERE latest.memory_id = d.id)
            """;
    private static final RowMapper<Entry> ENTRY = (rs, index) -> new Entry(rs.getString("id"),
            rs.getString("revision_id"), rs.getInt("version_no"), rs.getString("state"), rs.getString("category"),
            rs.getString("title"), rs.getString("content_md"), rs.getString("project_key"),
            rs.getString("source_agent"), rs.getString("change_summary"), rs.getBoolean("startup"),
            rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class));

    private final JdbcTemplate db;
    private final MemoryDocumentMapper documents;
    private final UnifiedMemoryService unified;

    public AgentMemoryService(JdbcTemplate db, MemoryDocumentMapper documents, UnifiedMemoryService unified) {
        this.db = db;
        this.documents = documents;
        this.unified = unified;
    }

    public record Input(String kind, String title, String content, String project, String source, String sourceSummary) {}
    public record Review(String action, String expectedRevision, String title, String content, String project,
            Boolean startup, String targetId, String expectedTargetRevision) {}
    public record Entry(String id, String revisionId, int version, String state, String kind, String title,
            String content, String project, String source, String sourceSummary, boolean startup,
            LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record Page(List<Entry> items, boolean hasMore) {}

    public Page list(String member, String state, String query, String project, String source, int offset) {
        return find(member, state == null ? "draft" : state, query, project, source, offset, 20, false, false);
    }

    /** Agent reads contain active entries only. Without a project key, only global entries are returned. */
    public Page search(String member, String query, String project, boolean startupOnly, int offset) {
        return find(member, "active", query, startupOnly ? "" : project, null, offset, 8, true, startupOnly);
    }

    private Page find(String member, String state, String query, String project, String source, int offset, int limit,
            boolean agent, boolean startupOnly) {
        if (!STATES.contains(state) || offset < 0) throw new BadRequestException("无效的记忆筛选条件");
        StringBuilder sql = new StringBuilder(SELECT).append(CURRENT).append(" AND d.state = ?");
        List<Object> params = new ArrayList<>(List.of(member, state));
        if (agent || project != null) {
            String key = projectKey(project);
            sql.append(agent ? " AND (a.project_key = '' OR a.project_key = ?)" : " AND a.project_key = ?");
            params.add(key);
        }
        if (source != null && !source.isBlank()) {
            if (!SOURCES.contains(source)) throw new BadRequestException("无效的记忆来源");
            sql.append(" AND a.source_agent = ?");
            params.add(source);
        }
        String needle = bounded(query, "关键词", 200, false);
        if (!needle.isEmpty()) {
            sql.append(" AND (LOCATE(LOWER(?), LOWER(r.title)) > 0 OR LOCATE(LOWER(?), LOWER(r.content_md)) > 0)");
            params.add(needle);
            params.add(needle);
        }
        if (startupOnly) sql.append(" AND a.startup = TRUE");
        sql.append(" ORDER BY d.updated_at DESC, d.id ASC LIMIT ? OFFSET ?");
        params.add(limit + 1);
        params.add(offset);
        List<Entry> entries = db.query(sql.toString(), ENTRY, params.toArray());
        return new Page(entries.size() > limit ? entries.subList(0, limit) : entries, entries.size() > limit);
    }

    public List<Entry> history(String member, String id) {
        List<Entry> entries = db.query(SELECT + " AND d.id = ? ORDER BY r.version_no DESC", ENTRY, member, id);
        if (entries.isEmpty()) throw missing();
        return entries;
    }

    @Transactional
    public Entry propose(String member, Input input) {
        if (input.kind() == null || !KINDS.contains(input.kind()) || input.source() == null || !SOURCES.contains(input.source())) {
            throw new BadRequestException("请选择有效的记忆类型和来源");
        }
        String project = projectKey(input.project());
        String title = bounded(input.title(), "标题", 200, true);
        String content = bounded(input.content(), "正文", 4000, true);
        String summary = bounded(input.sourceSummary(), "来源摘要", 480, false);
        rejectCredentials(title, content, summary);
        String fingerprint = UnifiedMemoryService.sha256(String.join("\u0000", member, input.kind(), project, title, content));
        String id = "am_" + fingerprint.substring(0, 60);
        // Serialize proposals for the same owner even when the derived document id does not exist yet.
        if (db.queryForList("SELECT id FROM member WHERE id = ? FOR UPDATE", String.class, member).isEmpty()) throw missing();
        List<MemoryDocumentEntity> existing = lock(member, id);
        if (!existing.isEmpty()) return current(member, id);
        LocalDateTime now = LocalDateTime.now();
        MemoryDocumentEntity document = new MemoryDocumentEntity();
        document.setId(id);
        document.setScopeType("agent");
        document.setScopeId(member);
        document.setCategory(input.kind());
        document.setState("draft");
        document.setCreatedBy(member);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        documents.insert(document);
        append(document, title, content, summary, project, input.source(), false, false);
        return current(member, id);
    }

    @Transactional
    public Entry review(String member, String id, Review review) {
        String action = review.action();
        if (action == null || !Set.of("publish", "revise", "merge", "reject", "archive").contains(action)) {
            throw new BadRequestException("无效的记忆操作");
        }
        boolean merge = "merge".equals(action);
        String targetId = merge ? bounded(review.targetId(), "合并目标", 64, true) : id;
        if (merge && id.equals(targetId)) throw new BadRequestException("请选择另一条已确认记忆");
        List<MemoryDocumentEntity> locked = merge ? lock(member, id, targetId) : lock(member, id);
        MemoryDocumentEntity original = document(locked, id);
        Entry candidate = current(member, id);
        boolean activeOnly = Set.of("archive", "revise").contains(action);
        requireVersion(candidate, review.expectedRevision(), activeOnly ? "active" : "draft");
        if ("reject".equals(action) || "archive".equals(action)) {
            changeState(original, "archive".equals(action) ? "archived" : "rejected");
            return current(member, id);
        }
        MemoryDocumentEntity target = document(locked, targetId);
        Entry entry = merge ? current(member, targetId) : candidate;
        if (merge) {
            requireVersion(entry, review.expectedTargetRevision(), "active");
            if (!entry.kind().equals(candidate.kind()) || !entry.project().equals(candidate.project())) {
                throw new BadRequestException("请选择相同类型和适用范围的记忆");
            }
        }
        String project = merge ? entry.project() : projectKey(review.project());
        String title = bounded(review.title(), "标题", 200, true);
        String content = bounded(review.content(), "正文", 4000, true);
        boolean startup = Boolean.TRUE.equals(review.startup());
        if (startup && (!project.isEmpty() || !"preference".equals(entry.kind()) || content.length() > 500)) {
            throw new BadRequestException("启动偏好须为 500 字以内的通用使用偏好");
        }
        rejectCredentials(title, content, candidate.sourceSummary());
        append(target, title, content, candidate.sourceSummary(), project, candidate.source(), startup, true);
        if (merge) changeState(original, "archived");
        return current(member, targetId);
    }

    private void append(MemoryDocumentEntity document, String title, String content, String summary, String project,
            String source, boolean startup, boolean confirmed) {
        SaveMemoryRequest data = new SaveMemoryRequest("agent", document.getScopeId(), document.getCategory(), title,
                content, summary, List.of(), null, document.getCurrentPublishedRevisionId());
        String revision = unified.appendAgentRevision(document, data,
                Set.of("codex", "agy").contains(source) ? "ai" : "human", confirmed);
        db.update("INSERT INTO agent_memory_revision (revision_id, project_key, source_agent, startup) VALUES (?, ?, ?, ?)",
                revision, project, source, startup);
    }

    private Entry current(String member, String id) {
        return db.query(SELECT + CURRENT + " AND d.id = ?", ENTRY, member, id)
                .stream().findFirst().orElseThrow(AgentMemoryService::missing);
    }

    private List<MemoryDocumentEntity> lock(String member, String... ids) {
        return documents.selectList(new LambdaQueryWrapper<MemoryDocumentEntity>()
                .eq(MemoryDocumentEntity::getScopeType, "agent").eq(MemoryDocumentEntity::getScopeId, member)
                .in(MemoryDocumentEntity::getId, Arrays.asList(ids))
                .orderByAsc(MemoryDocumentEntity::getId).last("FOR UPDATE"));
    }

    private static MemoryDocumentEntity document(List<MemoryDocumentEntity> documents, String id) {
        return documents.stream().filter(doc -> doc.getId().equals(id)).findFirst().orElseThrow(AgentMemoryService::missing);
    }

    private void changeState(MemoryDocumentEntity document, String state) {
        LocalDateTime now = LocalDateTime.now();
        document.setState(state);
        document.setUpdatedAt(now);
        if ("archived".equals(state)) document.setArchivedAt(now);
        documents.updateById(document);
    }

    private static void requireVersion(Entry entry, String expected, String state) {
        if (!Objects.equals(entry.revisionId(), expected) || !state.equals(entry.state())) {
            throw new ConflictException("记忆已更新，请对照最新版本后重试");
        }
    }

    private static String projectKey(String value) {
        String project = bounded(value, "项目标识", 120, false).toLowerCase(Locale.ROOT);
        if (!project.isEmpty() && !PROJECT_KEY.matcher(project).matches()) {
            throw new BadRequestException("项目标识请使用仓库名或组织/仓库名");
        }
        return project;
    }

    private static String bounded(String input, String label, int max, boolean required) {
        String value = input == null ? "" : input.strip();
        if (value.length() > max || (required && value.isEmpty()) || value.indexOf(0) >= 0) {
            throw new BadRequestException(label + "无效，长度上限为 " + max);
        }
        return value;
    }

    private static void rejectCredentials(String... values) {
        if (CREDENTIAL.matcher(String.join("\n", values)).find()) {
            throw new BadRequestException("记忆可能包含凭据，请移除后重试");
        }
    }

    private static NotFoundException missing() { return new NotFoundException("记忆不存在"); }
}
