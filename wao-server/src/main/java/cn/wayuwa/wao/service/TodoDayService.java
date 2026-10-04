package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.dto.TaskDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Bounded reads of recorded task facts and the member's canonical notes. */
@Service
public class TodoDayService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TaskService tasks;

    public TodoDayService(JdbcTemplate jdbc, ObjectMapper json, TaskService tasks) {
        this.jdbc = jdbc;
        this.json = json;
        this.tasks = tasks;
    }

    public record DayEvent(long id, String type, String occurredAt, LocalDate businessDate,
            boolean late, String summary) {}
    public record DayTask(TaskDto task, JsonNode snapshot, String source, List<DayEvent> events,
            boolean planned, boolean finished) {}
    public record DayNote(String docId, String title, String createdAt, String updatedAt,
            LocalDate businessDate, boolean late, String taskId) {}
    public record Summary(int plannedCount, int finishedCount, int stepDoneCount, int noteCount) {}
    public record Day(LocalDate date, String recordingStartedAt, String historyCoverage,
            List<DayTask> tasks, List<DayNote> notes, Summary summary) {}
    public record DaySummary(LocalDate date, int plannedCount, int finishedCount,
            int stepDoneCount, int noteCount, boolean hasHistory) {}
    public record Month(LocalDate from, LocalDate to, List<DaySummary> days) {}

    /** Reads one Shanghai business day, with current project permission applied to every task. */
    public Day day(LocalDate date, String memberId) {
        return day(date, memberId, visibleTasks(memberId), recordingStart(), true);
    }

    /** 工作区日读取叠加连接项目范围及小记知识库范围，事件和统计使用相同过滤。 */
    public Day day(LocalDate date, String memberId, java.util.function.Predicate<TaskDto> allowed, boolean includeNotes) {
        Map<String, TaskDto> visible = visibleTasks(memberId);
        visible.values().removeIf(t -> !allowed.test(t));
        return day(date, memberId, visible, recordingStart(), includeNotes);
    }

    /** Reads at most 31 days without expanding an unbounded historical range. */
    public Month month(LocalDate from, LocalDate to, String memberId) {
        return month(from, to, memberId, t -> true, true);
    }

    /** 工作区月摘要使用连接范围计算，不泄漏未授权项目的数量或小记。 */
    public Month month(LocalDate from, LocalDate to, String memberId, java.util.function.Predicate<TaskDto> allowed, boolean includeNotes) {
        if (from == null || to == null || to.isBefore(from) || to.toEpochDay() - from.toEpochDay() >= 31)
            throw new BadRequestException("日期范围最多 31 天");
        Map<String, TaskDto> visible = visibleTasks(memberId);
        visible.values().removeIf(t -> !allowed.test(t));
        LocalDateTime started = recordingStart();
        Map<LocalDate, Map<String, JsonNode>> dailySnapshots = new LinkedHashMap<>();
        Map<LocalDate, Set<String>> stepDone = new HashMap<>();
        Map<LocalDate, Set<String>> finishedFacts = new HashMap<>();
        Map<LocalDate, Integer> noteCount = new HashMap<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1))
            dailySnapshots.put(date, new LinkedHashMap<>());
        java.util.StringJoiner dayRows = new java.util.StringJoiner(" UNION ALL ");
        List<Object> versionArgs = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            dayRows.add("SELECT CAST(? AS DATE) AS day_date, CAST(? AS DATETIME) AS cutoff");
            versionArgs.add(date);
            versionArgs.add(date.plusDays(1).atStartOfDay());
        }
        versionArgs.add(memberId);
        String versionSql = "WITH days AS (" + dayRows + "), ranked AS (" + """
                  SELECT days.day_date, e.task_id, e.after_snapshot,
                         ROW_NUMBER() OVER (PARTITION BY days.day_date, e.task_id
                                            ORDER BY e.occurred_at DESC, e.id DESC) AS rn
                    FROM days JOIN wao_todo_task_day_event e
                      ON e.member_id = ? AND e.occurred_at < days.cutoff
                )
                SELECT day_date, task_id, after_snapshot FROM ranked
                 WHERE rn = 1 AND after_snapshot IS NOT NULL LIMIT 62001
                """;
        List<VersionRow> versions = jdbc.query(versionSql,
                (rs, row) -> new VersionRow(rs.getDate(1).toLocalDate(), rs.getString(2),
                parse(rs.getString(3))), versionArgs.toArray());
        if (versions.size() > 62000) throw new BadRequestException("月度任务记录超过安全读取上限");
        for (VersionRow version : versions) {
            if (visible.containsKey(version.taskId()) && belongs(version.snapshot(), memberId)
                    && planned(version.snapshot(), version.date()))
                dailySnapshots.get(version.date()).put(version.taskId(), version.snapshot());
        }
        List<MonthEventRow> events = jdbc.query("""
                SELECT business_date, task_id, event_type, before_snapshot, after_snapshot
                  FROM wao_todo_task_day_event
                 WHERE member_id = ? AND business_date BETWEEN ? AND ? AND event_type <> 'BASELINE'
                 ORDER BY id LIMIT 15501
                """, (rs, row) -> new MonthEventRow(rs.getDate(1).toLocalDate(), rs.getString(2),
                rs.getString(3), parse(rs.getString(4)), parse(rs.getString(5))), memberId, from, to);
        if (events.size() > 15500) throw new BadRequestException("月度事件超过安全读取上限");
        for (MonthEventRow event : events) {
            if (!visible.containsKey(event.taskId())) continue;
            Map<String, JsonNode> dayTasks = dailySnapshots.get(event.date());
            if (dayTasks == null) continue;
            JsonNode snapshot = event.after() == null ? event.before() : event.after();
            if (snapshot != null) dayTasks.putIfAbsent(event.taskId(), snapshot);
            if (event.before() != null && event.after() != null
                    && !finished(event.before()) && finished(event.after()))
                finishedFacts.computeIfAbsent(event.date(), ignored -> new HashSet<>()).add(event.taskId());
            if (event.before() != null && ("STEP_CHANGED".equals(event.type()) || "STEP_MOVED".equals(event.type())
                    || "TASK_CHANGED".equals(event.type())))
                stepDone.computeIfAbsent(event.date(), ignored -> new HashSet<>())
                        .addAll(newlyDoneIds(event.taskId(), event.before(), event.after()));
        }
        List<LocalDate> explicitNotes = includeNotes ? jdbc.query("""
                SELECT c.business_date FROM wao_todo_day_note_context c JOIN doc d ON d.id = c.doc_id
                 WHERE c.member_id = ? AND c.business_date BETWEEN ? AND ? AND d.author_id = ?
                   AND d.kb_id = 'kb-notes' AND d.deleted_at IS NULL AND d.archived_at IS NULL LIMIT 6201
                """, (rs, row) -> rs.getDate(1).toLocalDate(), memberId, from, to, memberId) : List.of();
        List<LocalDate> ordinaryNotes = includeNotes ? jdbc.query("""
                SELECT DATE(d.created_at) FROM doc d
                 WHERE d.author_id = ? AND d.kb_id = 'kb-notes'
                   AND d.created_at >= ? AND d.created_at < ?
                   AND d.deleted_at IS NULL AND d.archived_at IS NULL
                   AND NOT EXISTS (SELECT 1 FROM wao_todo_day_note_context c WHERE c.doc_id = d.id)
                 LIMIT 6201
                """, (rs, row) -> rs.getDate(1).toLocalDate(), memberId,
                from.atStartOfDay(), to.plusDays(1).atStartOfDay()) : List.of();
        if (explicitNotes.size() + ordinaryNotes.size() > 6200)
            throw new BadRequestException("月度小记超过安全读取上限");
        for (LocalDate date : explicitNotes) noteCount.merge(date, 1, Integer::sum);
        for (LocalDate date : ordinaryNotes) noteCount.merge(date, 1, Integer::sum);
        List<DaySummary> out = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            int plannedCount = 0;
            for (JsonNode snapshot : dailySnapshots.get(date).values()) {
                if (planned(snapshot, date)) plannedCount++;
            }
            int finishedCount = date.isAfter(LocalDate.now(SHANGHAI)) ? 0
                    : finishedFacts.getOrDefault(date, Set.of()).size();
            out.add(new DaySummary(date, plannedCount, finishedCount,
                    stepDone.getOrDefault(date, Set.of()).size(),
                    noteCount.getOrDefault(date, 0), !date.isBefore(started.toLocalDate())));
        }
        return new Month(from, to, out);
    }

    private record VersionRow(LocalDate date, String taskId, JsonNode snapshot) {}
    private record MonthEventRow(LocalDate date, String taskId, String type, JsonNode before, JsonNode after) {}

    private Day day(LocalDate date, String memberId, Map<String, TaskDto> visible, LocalDateTime started, boolean includeNotes) {
        if (date == null) throw new BadRequestException("日期无效");
        boolean beforeBaseline = date.isBefore(started.toLocalDate());
        Map<String, JsonNode> snapshots = new LinkedHashMap<>();
        if (!beforeBaseline) {
            List<Map.Entry<String, JsonNode>> versions = jdbc.query("""
                    SELECT task_id, after_snapshot AS snapshot FROM (
                      SELECT task_id, after_snapshot,
                             ROW_NUMBER() OVER (PARTITION BY task_id ORDER BY occurred_at DESC, id DESC) AS rn
                        FROM wao_todo_task_day_event
                       WHERE member_id = ? AND occurred_at < ?
                    ) ranked WHERE rn = 1 AND after_snapshot IS NOT NULL LIMIT 2001
                    """, (rs, row) -> Map.entry(rs.getString(1), parse(rs.getString(2))),
                    memberId, date.plusDays(1).atStartOfDay());
            if (versions.size() > 2000) throw new BadRequestException("日任务记录超过安全读取上限");
            for (Map.Entry<String, JsonNode> version : versions) {
                if (visible.containsKey(version.getKey()) && planned(version.getValue(), date)
                        && belongs(version.getValue(), memberId)) snapshots.put(version.getKey(), version.getValue());
            }
        }
        List<EventRow> events = jdbc.query("""
                SELECT id, task_id, event_type, occurred_at, business_date,
                       after_snapshot, before_snapshot
                  FROM wao_todo_task_day_event
                 WHERE member_id = ? AND business_date = ? AND event_type <> 'BASELINE'
                 ORDER BY id LIMIT 501
                """, (rs, row) -> new EventRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4).toLocalDateTime(), rs.getDate(5).toLocalDate(),
                parse(rs.getString(6)), parse(rs.getString(7))), memberId, date);
        if (events.size() > 500) throw new BadRequestException("当日事件超过安全读取上限");
        Map<String, List<DayEvent>> byTask = new HashMap<>();
        Set<String> plannedKeys = new HashSet<>(snapshots.keySet());
        Set<String> stepDone = new HashSet<>();
        Set<String> finishedFacts = new HashSet<>();
        for (EventRow event : events) {
            if (!visible.containsKey(event.taskId())) continue;
            if (!plannedKeys.contains(event.taskId())) {
                JsonNode snapshot = event.after() == null ? event.before() : event.after();
                if (snapshot != null) snapshots.put(event.taskId(), snapshot);
            }
            boolean late = !event.occurredAt().toLocalDate().equals(event.businessDate());
            byTask.computeIfAbsent(event.taskId(), ignored -> new ArrayList<>()).add(new DayEvent(event.id(),
                    event.type(), timestamp(event.occurredAt()), event.businessDate(), late, label(event.type())));
            if (event.before() != null && event.after() != null
                    && !finished(event.before()) && finished(event.after())) finishedFacts.add(event.taskId());
            if (event.before() != null && ("STEP_CHANGED".equals(event.type()) || "STEP_MOVED".equals(event.type())
                    || "TASK_CHANGED".equals(event.type())))
                stepDone.addAll(newlyDoneIds(event.taskId(), event.before(), event.after()));
        }
        List<DayTask> taskList = new ArrayList<>();
        int plannedCount = 0;
        for (Map.Entry<String, JsonNode> entry : snapshots.entrySet()) {
            TaskDto task = visible.get(entry.getKey());
            if (task == null) continue;
            JsonNode snapshot = entry.getValue();
            boolean isPlanned = planned(snapshot, date);
            boolean finished = finished(snapshot);
            if (isPlanned) plannedCount++;
            taskList.add(new DayTask(task, snapshot, date.isBefore(LocalDate.now(SHANGHAI)) ? "RECORDED" : "CURRENT",
                    byTask.getOrDefault(entry.getKey(), List.of()), isPlanned, finished));
        }
        List<DayNote> notes = includeNotes ? notes(date, memberId, visible) : List.of();
        return new Day(date, timestamp(started), beforeBaseline ? "BEFORE_BASELINE" : "RECORDED",
                taskList, notes, new Summary(plannedCount,
                        date.isAfter(LocalDate.now(SHANGHAI)) ? 0 : finishedFacts.size(),
                        stepDone.size(), notes.size()));
    }

    private Map<String, TaskDto> visibleTasks(String memberId) {
        Map<String, TaskDto> visible = new HashMap<>();
        for (TaskDto task : tasks.list(memberId)) visible.put(task.id(), task);
        return visible;
    }

    private LocalDateTime recordingStart() {
        return jdbc.queryForObject("SELECT started_at FROM wao_todo_recording_scope WHERE id = 1",
                LocalDateTime.class);
    }

    private List<DayNote> notes(LocalDate date, String memberId, Map<String, TaskDto> visible) {
        List<NoteRow> rows = new ArrayList<>();
        rows.addAll(jdbc.query("""
                SELECT d.id, d.title, d.created_at, d.updated_at, c.business_date, c.late, c.task_id
                  FROM wao_todo_day_note_context c JOIN doc d ON d.id = c.doc_id
                 WHERE c.member_id = ? AND c.business_date = ? AND d.author_id = ?
                   AND d.kb_id = 'kb-notes' AND d.deleted_at IS NULL AND d.archived_at IS NULL
                 ORDER BY d.created_at, d.id LIMIT 201
                """, this::noteRow, memberId, date, memberId));
        rows.addAll(jdbc.query("""
                SELECT d.id, d.title, d.created_at, d.updated_at, DATE(d.created_at), 0, NULL
                  FROM doc d
                 WHERE d.author_id = ? AND d.kb_id = 'kb-notes'
                   AND d.created_at >= ? AND d.created_at < ?
                   AND d.deleted_at IS NULL AND d.archived_at IS NULL
                   AND NOT EXISTS (SELECT 1 FROM wao_todo_day_note_context c WHERE c.doc_id = d.id)
                 ORDER BY d.created_at, d.id LIMIT 201
                """, this::noteRow, memberId, date.atStartOfDay(), date.plusDays(1).atStartOfDay()));
        if (rows.size() > 200) throw new BadRequestException("当日小记超过安全读取上限");
        List<DayNote> out = new ArrayList<>();
        for (NoteRow row : rows) {
            String taskId = row.taskId();
            if (taskId != null) {
                TaskDto task = visible.get(taskId);
                if (task == null || task.docs() == null || !task.docs().contains(row.id())) taskId = null;
            }
            out.add(new DayNote(row.id(), row.title(), timestamp(row.createdAt()), timestamp(row.updatedAt()),
                    row.date(), row.late(), taskId));
        }
        return out;
    }

    private NoteRow noteRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Timestamp created = rs.getTimestamp(3), updated = rs.getTimestamp(4);
        return new NoteRow(rs.getString(1), rs.getString(2), created == null ? null : created.toLocalDateTime(),
                updated == null ? null : updated.toLocalDateTime(), rs.getDate(5).toLocalDate(),
                rs.getBoolean(6), rs.getString(7));
    }

    private record NoteRow(String id, String title, LocalDateTime createdAt, LocalDateTime updatedAt,
            LocalDate date, boolean late, String taskId) {}
    private record EventRow(long id, String taskId, String type, LocalDateTime occurredAt,
            LocalDate businessDate, JsonNode after, JsonNode before) {}

    private JsonNode parse(String source) {
        if (source == null) return null;
        try { return json.readTree(source); }
        catch (Exception ex) { throw new IllegalStateException("无法读取日历史快照", ex); }
    }

    private static boolean belongs(JsonNode snapshot, String memberId) {
        if (snapshot == null) return false;
        if (memberId.equals(snapshot.path("owner").asText())) return true;
        JsonNode parts = snapshot.path("parts");
        for (JsonNode part : parts) if (memberId.equals(part.asText())) return true;
        return false;
    }

    private static boolean planned(JsonNode snapshot, LocalDate date) {
        if (snapshot == null) return false;
        String start = snapshot.path("start").asText("");
        if (start.isBlank()) return false;
        try {
            LocalDate first = LocalDate.parse(start);
            if (first.equals(date)) return true;
            if (date.isBefore(first)) return false;
            String end = snapshot.path("endAt").asText("");
            if (end.isBlank()) return false;
            LocalDateTime endAt = LocalDateTime.parse(end);
            return date.atStartOfDay().isBefore(endAt);
        } catch (RuntimeException ex) { return false; }
    }

    private static boolean finished(JsonNode snapshot) {
        if (snapshot == null || !"已完成".equals(snapshot.path("status").asText())) return false;
        for (JsonNode sub : snapshot.path("subs")) if (!sub.path("done").asBoolean(false)) return false;
        return true;
    }

    private static Set<String> newlyDoneIds(String taskId, JsonNode before, JsonNode after) {
        Set<String> result = new HashSet<>();
        if (after == null) return result;
        Map<String, Boolean> old = new HashMap<>();
        if (before != null) {
            int oldIndex = 0;
            for (JsonNode step : before.path("subs")) {
                String key = step.path("id").asText("");
                if (key.isBlank()) key = "legacy-" + oldIndex;
                old.put(key, step.path("done").asBoolean(false));
                oldIndex++;
            }
        }
        int index = 0;
        for (JsonNode step : after.path("subs")) {
            String key = step.path("id").asText("");
            if (key.isBlank()) key = "legacy-" + index;
            if (step.path("done").asBoolean(false) && !old.getOrDefault(key, false))
                result.add(taskId + "/" + key);
            index++;
        }
        return result;
    }

    private static String label(String type) {
        return switch (type) {
            case "CREATED" -> "创建任务";
            case "SCHEDULE_CHANGED" -> "调整安排";
            case "STEP_CHANGED" -> "更新步骤";
            case "STEP_MOVED" -> "移动步骤";
            case "HIERARCHY_CHANGED" -> "调整任务层级";
            case "NOTE_LINKED" -> "记录进展";
            case "DELETED" -> "删除任务";
            default -> "更新任务";
        };
    }

    private static String timestamp(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.ofHours(8)).toString();
    }
}
