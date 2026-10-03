package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Member-private routine rules and deliberate occurrence status, without ordinary task copies. */
@Service
public class DailyRoutineService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String RULE = "ROUTINE_RULE";
    private static final String OCCURRENCE = "ROUTINE_OCCURRENCE";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public DailyRoutineService(JdbcTemplate jdbc, ObjectMapper json) {
        this(jdbc, json, Clock.system(SHANGHAI));
    }

    DailyRoutineService(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public record RuleRequest(Integer expectedVersion, String title, String period,
            List<Integer> weekdays, Integer sortOrder, Boolean active, Boolean archived) {}
    public record StatusRequest(Integer expectedVersion, String status) {}
    public record Revision(String id, LocalDate effectiveFrom, LocalDate effectiveTo,
            String title, String period, List<Integer> weekdays, int sortOrder,
            boolean active, boolean archived) {}
    public record Template(String id, int version, Revision currentRevision, Revision nextRevision) {}
    public record Occurrence(String templateId, String revisionId, LocalDate date, String title,
            String period, int sortOrder, String status, int version, String recordedAt,
            boolean virtual) {}
    public record Day(LocalDate date, List<Occurrence> occurrences) {}
    public record Range(LocalDate from, LocalDate to, List<Template> templates, List<Day> days) {}
    public record RuleResult(String operationId, Template template, LocalDate effectiveFrom) {}
    public record OccurrenceResult(String operationId, Occurrence occurrence) {}
    public record Receipt(String status, String operationId, String type, Object result) {}
    private record RuleRef(String templateId, LocalDate effectiveFrom) {}
    private record OccurrenceRef(String templateId, LocalDate date) {}
    private record StoredReceipt(String type, String hash, String resultJson) {}
    private record TemplateRow(String id, int version) {}
    private record RevisionRow(String id, String templateId, Revision value) {}

    /** Reads at most 31 Shanghai business days; unvisited occurrences remain deterministic virtual rows. */
    public Range range(LocalDate from, LocalDate to, String memberId) {
        requireMember(memberId);
        if (from == null || to == null || to.isBefore(from) || to.toEpochDay() - from.toEpochDay() >= 31)
            throw new BadRequestException("日常规则日期范围最多 31 天");
        List<TemplateRow> templates = jdbc.query("""
                SELECT id, version FROM wao_todo_routine_template
                 WHERE member_id = ? ORDER BY created_at, id LIMIT 501
                """, (rs, i) -> new TemplateRow(rs.getString(1), rs.getInt(2)), memberId);
        if (templates.size() > 500) throw new BadRequestException("日常规则超过安全读取上限");
        LocalDate today = today();
        LocalDate earliest = from.isBefore(today) ? from : today;
        LocalDate latest = to.isAfter(today.plusDays(1)) ? to : today.plusDays(1);
        List<RevisionRow> revisions = jdbc.query("""
                SELECT id, template_id, effective_from, effective_to, title, period,
                       weekdays_json, sort_order, active, archived
                  FROM wao_todo_routine_revision
                 WHERE member_id = ? AND effective_from <= ?
                   AND (effective_to IS NULL OR effective_to > ?)
                 ORDER BY template_id, effective_from LIMIT 5001
                """, (rs, i) -> new RevisionRow(rs.getString(1), rs.getString(2),
                new Revision(rs.getString(1), rs.getDate(3).toLocalDate(), dateOrNull(rs.getDate(4)),
                        rs.getString(5), rs.getString(6), weekdays(rs.getString(7)), rs.getInt(8),
                        rs.getBoolean(9), rs.getBoolean(10))), memberId, latest, earliest);
        if (revisions.size() > 5000) throw new BadRequestException("日常规则版本超过安全读取上限");
        Map<String, List<Revision>> byTemplate = new HashMap<>();
        for (RevisionRow row : revisions)
            byTemplate.computeIfAbsent(row.templateId(), ignored -> new ArrayList<>()).add(row.value());
        List<Template> shownTemplates = new ArrayList<>();
        for (TemplateRow row : templates)
            shownTemplates.add(template(row, byTemplate.getOrDefault(row.id(), List.of()), today));
        List<Occurrence> persisted = jdbc.query("""
                SELECT template_id, revision_id, business_date, title, period, sort_order,
                       status, version, recorded_at
                  FROM wao_todo_routine_occurrence
                 WHERE member_id = ? AND business_date BETWEEN ? AND ?
                 ORDER BY business_date, period, sort_order, template_id LIMIT 5001
                """, (rs, i) -> new Occurrence(rs.getString(1), rs.getString(2),
                rs.getDate(3).toLocalDate(), rs.getString(4), rs.getString(5), rs.getInt(6),
                rs.getString(7), rs.getInt(8), time(rs.getTimestamp(9).toLocalDateTime()), false),
                memberId, from, to);
        if (persisted.size() > 5000) throw new BadRequestException("日常记录超过安全读取上限");
        Map<LocalDate, Map<String, Occurrence>> byDay = new HashMap<>();
        for (Occurrence row : persisted)
            byDay.computeIfAbsent(row.date(), ignored -> new HashMap<>()).put(row.templateId(), row);
        List<Day> days = new ArrayList<>();
        int shownOccurrences = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            Map<String, Occurrence> rows = byDay.computeIfAbsent(date, ignored -> new HashMap<>());
            for (TemplateRow row : templates) {
                if (rows.containsKey(row.id())) continue;
                Revision revision = asOf(byTemplate.get(row.id()), date);
                if (eligible(revision, date)) rows.put(row.id(), virtual(row.id(), date, revision));
            }
            List<Occurrence> sorted = new ArrayList<>(rows.values());
            sorted.sort(Comparator.comparingInt((Occurrence o) -> "MORNING".equals(o.period()) ? 0 : 1)
                    .thenComparingInt(Occurrence::sortOrder).thenComparing(Occurrence::templateId));
            shownOccurrences += sorted.size();
            if (shownOccurrences > 5000) throw new BadRequestException("日常记录超过安全读取上限");
            days.add(new Day(date, sorted));
        }
        return new Range(from, to, shownTemplates, days);
    }

    /** Reads one rule's current version and next-day proposal within the member boundary. */
    public Template get(String id, String memberId) {
        requireMember(memberId);
        TemplateRow row = owned(id, memberId, false);
        List<Revision> revisions = revisions(id, memberId);
        return template(row, revisions, today());
    }

    /** Creates a rule effective today, or changes an existing rule starting tomorrow, using CAS and a retry key. */
    @Transactional
    public RuleResult putRule(String id, String operationId, RuleRequest request,
            String accountId, String memberId) {
        requireMember(memberId);
        requireUuid(id);
        requireUuid(operationId);
        validateRule(request);
        lockAccount(accountId);
        String hash = hash(RULE, id, request);
        StoredReceipt prior = stored(accountId, operationId);
        if (prior != null) {
            checkReplay(prior, RULE, hash);
            RuleRef ref = read(prior.resultJson(), RuleRef.class);
            return new RuleResult(operationId, get(ref.templateId(), memberId), ref.effectiveFrom());
        }
        LocalDate today = today();
        LocalDateTime now = LocalDateTime.now(clock);
        TemplateRow existing = owned(id, memberId, true);
        LocalDate effective;
        if (existing == null) {
            if (!jdbc.query("SELECT id FROM wao_todo_routine_template WHERE id = ?",
                    (rs, i) -> rs.getString(1), id).isEmpty())
                throw new NotFoundException("日常规则不存在");
            if (request.expectedVersion() != null)
                throw new ConflictException("规则已改变，请刷新后重试");
            effective = today;
            jdbc.update("""
                    INSERT INTO wao_todo_routine_template (id, member_id, version, created_at, updated_at)
                    VALUES (?, ?, 1, ?, ?)
                    """, id, memberId, now, now);
            insertRevision(id, memberId, effective, request, now);
        } else {
            if (!Objects.equals(existing.version(), request.expectedVersion()))
                throw new ConflictException("规则已改变，请刷新后重试");
            effective = today.plusDays(1);
            List<Revision> old = revisions(id, memberId);
            Revision future = asOf(old, effective);
            if (future != null && future.effectiveFrom().equals(effective)) {
                jdbc.update("""
                        UPDATE wao_todo_routine_revision
                           SET title = ?, period = ?, weekdays_json = ?, sort_order = ?,
                               active = ?, archived = ?
                         WHERE id = ? AND member_id = ?
                        """, request.title().trim(), request.period(), write(request.weekdays()),
                        request.sortOrder(), request.active() && !request.archived(), request.archived(), future.id(), memberId);
            } else {
                jdbc.update("""
                        UPDATE wao_todo_routine_revision SET effective_to = ?
                         WHERE template_id = ? AND member_id = ? AND effective_to IS NULL
                        """, effective, id, memberId);
                insertRevision(id, memberId, effective, request, now);
            }
            int updated = jdbc.update("""
                    UPDATE wao_todo_routine_template SET version = version + 1, updated_at = ?
                     WHERE id = ? AND member_id = ? AND version = ?
                    """, now, id, memberId, existing.version());
            if (updated != 1) throw new ConflictException("规则已改变，请刷新后重试");
        }
        save(accountId, operationId, RULE, hash, new RuleRef(id, effective));
        return new RuleResult(operationId, get(id, memberId), effective);
    }

    /** Changes today's instance only; past and future dates are read-only, and virtual rows use version zero. */
    @Transactional
    public OccurrenceResult putOccurrence(String id, LocalDate date, String operationId,
            StatusRequest request, String accountId, String memberId) {
        requireMember(memberId);
        requireUuid(id);
        requireUuid(operationId);
        if (date == null) throw new BadRequestException("日常日期无效");
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0
                || request.status() == null || !Set.of("OPEN", "DONE", "SKIPPED").contains(request.status()))
            throw new BadRequestException("日常状态或版本无效");
        lockAccount(accountId);
        String hash = hash(OCCURRENCE, id + "/" + date, request);
        StoredReceipt prior = stored(accountId, operationId);
        if (prior != null) {
            checkReplay(prior, OCCURRENCE, hash);
            OccurrenceRef ref = read(prior.resultJson(), OccurrenceRef.class);
            return new OccurrenceResult(operationId, occurrence(ref.templateId(), ref.date(), memberId));
        }
        if (!date.equals(today())) throw new BadRequestException("只能记录今天的日常状态");
        owned(id, memberId, false);
        Occurrence current = occurrence(id, date, memberId);
        if (current == null) throw new ConflictException("今天没有这条日常规则");
        if (current.version() != request.expectedVersion())
            throw new ConflictException("日常状态已改变，请刷新后重试");
        LocalDateTime now = LocalDateTime.now(clock);
        if (current.virtual()) {
            jdbc.update("""
                    INSERT INTO wao_todo_routine_occurrence
                      (template_id, member_id, business_date, revision_id, title, period,
                       sort_order, status, version, recorded_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                    """, id, memberId, date, current.revisionId(), current.title(), current.period(),
                    current.sortOrder(), request.status(), now);
        } else {
            int updated = jdbc.update("""
                    UPDATE wao_todo_routine_occurrence
                       SET status = ?, version = version + 1, recorded_at = ?
                     WHERE template_id = ? AND member_id = ? AND business_date = ? AND version = ?
                    """, request.status(), now, id, memberId, date, current.version());
            if (updated != 1) throw new ConflictException("日常状态已改变，请刷新后重试");
        }
        save(accountId, operationId, OCCURRENCE, hash, new OccurrenceRef(id, date));
        return new OccurrenceResult(operationId, occurrence(id, date, memberId));
    }

    /** Resolves this service's receipt after current member authorization; unknown keys stay unconfirmed. */
    public Receipt receipt(String operationId, String accountId, String memberId) {
        requireMember(memberId);
        requireUuid(operationId);
        StoredReceipt stored = stored(accountId, operationId);
        if (stored == null || (!RULE.equals(stored.type()) && !OCCURRENCE.equals(stored.type())))
            return new Receipt("UNCONFIRMED", operationId, null, null);
        try {
            if (RULE.equals(stored.type())) {
                RuleRef ref = read(stored.resultJson(), RuleRef.class);
                return new Receipt("COMMITTED", operationId, RULE,
                        new RuleResult(operationId, get(ref.templateId(), memberId), ref.effectiveFrom()));
            }
            OccurrenceRef ref = read(stored.resultJson(), OccurrenceRef.class);
            Occurrence row = occurrence(ref.templateId(), ref.date(), memberId);
            if (row == null) return new Receipt("UNCONFIRMED", operationId, null, null);
            return new Receipt("COMMITTED", operationId, OCCURRENCE,
                    new OccurrenceResult(operationId, row));
        } catch (NotFoundException ex) {
            return new Receipt("UNCONFIRMED", operationId, null, null);
        }
    }

    private Occurrence occurrence(String id, LocalDate date, String memberId) {
        owned(id, memberId, false);
        List<Occurrence> rows = jdbc.query("""
                SELECT template_id, revision_id, business_date, title, period, sort_order,
                       status, version, recorded_at
                  FROM wao_todo_routine_occurrence
                 WHERE template_id = ? AND member_id = ? AND business_date = ?
                """, (rs, i) -> new Occurrence(rs.getString(1), rs.getString(2),
                rs.getDate(3).toLocalDate(), rs.getString(4), rs.getString(5), rs.getInt(6),
                rs.getString(7), rs.getInt(8), time(rs.getTimestamp(9).toLocalDateTime()), false),
                id, memberId, date);
        if (!rows.isEmpty()) return rows.get(0);
        Revision revision = asOf(revisions(id, memberId), date);
        return eligible(revision, date) ? virtual(id, date, revision) : null;
    }

    private void insertRevision(String id, String memberId, LocalDate effective,
            RuleRequest request, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO wao_todo_routine_revision
                  (id, template_id, member_id, effective_from, effective_to, title, period,
                   weekdays_json, sort_order, active, archived, created_at)
                VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), id, memberId, effective, request.title().trim(),
                request.period(), write(request.weekdays()), request.sortOrder(), request.active() && !request.archived(),
                request.archived(), now);
    }

    private TemplateRow owned(String id, String memberId, boolean lock) {
        List<TemplateRow> rows = jdbc.query("""
                SELECT id, version FROM wao_todo_routine_template WHERE id = ? AND member_id = ?
                """ + (lock ? " FOR UPDATE" : ""),
                (rs, i) -> new TemplateRow(rs.getString(1), rs.getInt(2)), id, memberId);
        if (rows.isEmpty() && !lock) throw new NotFoundException("日常规则不存在");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Revision> revisions(String id, String memberId) {
        return jdbc.query("""
                SELECT id, effective_from, effective_to, title, period, weekdays_json,
                       sort_order, active, archived
                  FROM wao_todo_routine_revision
                 WHERE template_id = ? AND member_id = ? ORDER BY effective_from
                """, (rs, i) -> new Revision(rs.getString(1), rs.getDate(2).toLocalDate(),
                dateOrNull(rs.getDate(3)), rs.getString(4), rs.getString(5),
                weekdays(rs.getString(6)), rs.getInt(7), rs.getBoolean(8), rs.getBoolean(9)), id, memberId);
    }

    private Template template(TemplateRow row, List<Revision> revisions, LocalDate today) {
        Revision current = asOf(revisions, today);
        Revision next = revisions.stream().filter(r -> r.effectiveFrom().isAfter(today))
                .findFirst().orElse(null);
        return new Template(row.id(), row.version(), current, next);
    }

    private static Revision asOf(List<Revision> revisions, LocalDate date) {
        if (revisions == null) return null;
        for (Revision row : revisions) {
            if (!row.effectiveFrom().isAfter(date)
                    && (row.effectiveTo() == null || row.effectiveTo().isAfter(date))) return row;
        }
        return null;
    }

    private static boolean eligible(Revision revision, LocalDate date) {
        return revision != null && revision.active() && !revision.archived()
                && revision.weekdays().contains(date.getDayOfWeek().getValue());
    }

    private static Occurrence virtual(String id, LocalDate date, Revision revision) {
        return new Occurrence(id, revision.id(), date, revision.title(), revision.period(),
                revision.sortOrder(), "OPEN", 0, null, true);
    }

    private void validateRule(RuleRequest request) {
        if (request == null || request.expectedVersion() != null && request.expectedVersion() < 1
                || !StringUtils.hasText(request.title()) || request.title().trim().length() > 120
                || request.period() == null || !Set.of("MORNING", "EVENING").contains(request.period())
                || request.weekdays() == null || request.weekdays().isEmpty()
                || request.weekdays().size() > 7 || request.sortOrder() == null
                || request.sortOrder() < 0 || request.sortOrder() > 10000
                || request.active() == null || request.archived() == null)
            throw new BadRequestException("日常规则字段无效");
        Set<Integer> unique = new HashSet<>(request.weekdays());
        if (unique.size() != request.weekdays().size()
                || unique.stream().anyMatch(day -> day == null || day < 1 || day > 7))
            throw new BadRequestException("weekdays 必须是 1 至 7 的不重复星期值");
    }

    private void requireMember(String memberId) {
        if (!StringUtils.hasText(memberId)) throw new NotFoundException("请先登录");
    }

    private static void requireUuid(String id) {
        try {
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        } catch (RuntimeException ex) {
            throw new BadRequestException("规则 id 与 operationId 必须是规范 UUID");
        }
    }

    private void lockAccount(String accountId) {
        if (!StringUtils.hasText(accountId) || jdbc.query("SELECT id FROM account WHERE id = ? FOR UPDATE",
                (rs, i) -> rs.getString(1), accountId).isEmpty())
            throw new NotFoundException("账号不存在");
    }

    private StoredReceipt stored(String accountId, String operationId) {
        return jdbc.query("""
                SELECT operation_type, request_hash, result_json FROM wao_todo_operation_receipt
                 WHERE account_id = ? AND operation_id = ?
                """, (rs, i) -> new StoredReceipt(rs.getString(1), rs.getString(2), rs.getString(3)),
                accountId, operationId).stream().findFirst().orElse(null);
    }

    private static void checkReplay(StoredReceipt stored, String type, String hash) {
        if (!type.equals(stored.type()) || !hash.equals(stored.hash()))
            throw new ConflictException("operationId 已用于另一请求，请创建新的操作");
    }

    private void save(String accountId, String operationId, String type, String hash, Object ref) {
        jdbc.update("""
                INSERT INTO wao_todo_operation_receipt
                  (account_id, operation_id, operation_type, request_hash, result_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, accountId, operationId, type, hash, write(ref), LocalDateTime.now(clock));
    }

    private String hash(String type, String target, Object request) {
        try {
            byte[] bytes = json.writeValueAsBytes(List.of(type, target, request));
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("无法计算日常操作摘要", ex);
        }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("无法序列化日常规则", ex); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception ex) { throw new IllegalStateException("无法读取日常操作回执", ex); }
    }

    private static List<Integer> weekdays(String value) {
        try { return new ObjectMapper().readValue(value,
                new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>() {}); }
        catch (Exception ex) { throw new IllegalStateException("无法读取星期规则", ex); }
    }

    private static LocalDate dateOrNull(java.sql.Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private static String time(LocalDateTime value) {
        return value.atZone(SHANGHAI).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SHANGHAI));
    }
}
