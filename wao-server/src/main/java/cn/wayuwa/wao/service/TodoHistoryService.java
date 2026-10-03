package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.TaskDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Records the small set of task facts needed for a truthful daily review. */
@Service
public class TodoHistoryService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public TodoHistoryService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Writes an immutable task change and today's materialized fact in the caller's transaction. */
    public void record(TaskEntity before, TaskEntity after, String actor, String type, String operationId) {
        recordForDay(before, after, actor, type, operationId, LocalDate.now(SHANGHAI), false);
    }

    /** Records a note linked to a chosen day; a late note keeps its true write time. */
    public void recordProgress(TaskEntity task, String actor, String operationId, LocalDate businessDate) {
        recordForDay(task, task, actor, "NOTE_LINKED", operationId, businessDate, true);
    }

    private void recordForDay(TaskEntity before, TaskEntity after, String actor, String type,
            String operationId, LocalDate businessDate, boolean force) {
        if (before == null && after == null) return;
        LocalDateTime at = LocalDateTime.now(SHANGHAI);
        String oldSnapshot = before == null ? null : snapshot(before);
        String newSnapshot = after == null ? null : snapshot(after);
        if (!force && Objects.equals(oldSnapshot, newSnapshot)) return;
        Set<String> beforeMembers = members(before);
        Set<String> afterMembers = members(after);
        Set<String> members = new HashSet<>(beforeMembers);
        members.addAll(afterMembers);
        for (String member : members) {
            String memberBefore = beforeMembers.contains(member) ? oldSnapshot : null;
            String memberAfter = afterMembers.contains(member) ? newSnapshot : null;
            jdbc.update("""
                    INSERT INTO wao_todo_task_day_event
                      (member_id, task_id, occurred_at, business_date, event_type, actor_id,
                       before_snapshot, after_snapshot, operation_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, member, before == null ? after.getId() : before.getId(), at, businessDate,
                    type, actor, memberBefore, memberAfter, operationId);
            String current = memberAfter == null ? memberBefore : memberAfter;
            jdbc.update("""
                    INSERT INTO wao_todo_task_day_entry
                      (member_id, task_id, business_date, first_at, last_at, snapshot)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE last_at = VALUES(last_at), snapshot = VALUES(snapshot)
                    """, member, before == null ? after.getId() : before.getId(), businessDate, at, at, current);
        }
    }

    /** Captures a pre-write entity so mutable ORM objects cannot erase the old state. */
    public TaskEntity copy(TaskEntity e) {
        if (e == null) return null;
        TaskEntity copy = new TaskEntity();
        copy.setId(e.getId());
        copy.setTitle(e.getTitle());
        copy.setProjectId(e.getProjectId());
        copy.setStatus(e.getStatus());
        copy.setOwnerId(e.getOwnerId());
        copy.setStartDate(e.getStartDate());
        copy.setDetailJson(e.getDetailJson());
        copy.setParentTaskId(e.getParentTaskId());
        return copy;
    }

    private Set<String> members(TaskEntity entity) {
        Set<String> result = new HashSet<>();
        if (entity == null) return result;
        if (entity.getOwnerId() != null && !entity.getOwnerId().isBlank()) result.add(entity.getOwnerId());
        TaskDetail detail = detail(entity);
        if (detail.parts() != null) {
            for (String part : detail.parts()) if (part != null && !part.isBlank()) result.add(part);
        }
        return result;
    }

    private String snapshot(TaskEntity entity) {
        try {
            TaskDetail detail = detail(entity);
            return json.writeValueAsString(new Snapshot(entity.getTitle(), entity.getStatus(), entity.getOwnerId(),
                    detail.parts() == null ? List.of() : detail.parts(),
                    detail.steps() == null ? List.of() : detail.steps(),
                    detail.subs() == null ? List.of() : detail.subs(), entity.getStartDate(),
                    detail.startAt(), detail.endAt(), detail.schedulePeriod(), entity.getParentTaskId(),
                    entity.getProjectId()));
        } catch (Exception ex) {
            throw new IllegalStateException("无法记录任务日历史", ex);
        }
    }

    private TaskDetail detail(TaskEntity entity) {
        try {
            return entity.getDetailJson() == null ? json.readValue("{}", TaskDetail.class)
                    : json.readValue(entity.getDetailJson(), TaskDetail.class);
        } catch (Exception ex) {
            throw new IllegalStateException("无法读取任务详情以记录日历史", ex);
        }
    }

    public record Snapshot(String title, String status, String owner, List<String> parts,
            List<?> steps, List<?> subs, String start, String startAt, String endAt,
            String schedulePeriod, String parentTaskId, String project) {}
}
