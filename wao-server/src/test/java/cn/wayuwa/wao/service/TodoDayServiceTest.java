package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.dto.TaskDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TodoDayServiceTest {
    @Test
    void historicalPlanUsesThenCurrentSnapshotAndLaterMoveKeepsItsOwnEvent() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:todo_day_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        LocalDate first = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(3);
        LocalDate second = first.plusDays(1);
        LocalDate third = first.plusDays(2);
        jdbc.execute("CREATE TABLE wao_todo_recording_scope (id INT PRIMARY KEY, started_at TIMESTAMP)");
        jdbc.update("INSERT INTO wao_todo_recording_scope VALUES (1, ?)", first.atTime(8, 0));
        jdbc.execute("""
                CREATE TABLE wao_todo_task_day_event (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id VARCHAR(64), task_id VARCHAR(64),
                  occurred_at TIMESTAMP, business_date DATE, event_type VARCHAR(32),
                  before_snapshot VARCHAR(4000), after_snapshot VARCHAR(4000))
                """);
        jdbc.execute("""
                CREATE TABLE wao_todo_day_note_context (
                  doc_id VARCHAR(32), member_id VARCHAR(64), business_date DATE,
                  late BOOLEAN, task_id VARCHAR(64))
                """);
        jdbc.execute("""
                CREATE TABLE doc (id VARCHAR(32), title VARCHAR(256), created_at TIMESTAMP,
                  updated_at TIMESTAMP, author_id VARCHAR(64), kb_id VARCHAR(64),
                  deleted_at TIMESTAMP, archived_at TIMESTAMP)
                """);
        String old = "{\"title\":\"旧标题\",\"status\":\"待办\",\"owner\":\"wg\",\"parts\":[],\"subs\":[],\"start\":\"" + first + "\"}";
        String changed = "{\"title\":\"新标题\",\"status\":\"待办\",\"owner\":\"wg\",\"parts\":[],\"subs\":[],\"start\":\"" + third + "\"}";
        jdbc.update("INSERT INTO wao_todo_task_day_event (member_id, task_id, occurred_at, business_date, event_type, after_snapshot) VALUES (?, ?, ?, ?, ?, ?)",
                "wg", "t1", first.atTime(8, 0), first, "BASELINE", old);
        jdbc.update("INSERT INTO wao_todo_task_day_event (member_id, task_id, occurred_at, business_date, event_type, before_snapshot, after_snapshot) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "wg", "t1", second.atTime(9, 0), second, "SCHEDULE_CHANGED", old, changed);
        String done = changed.replace("待办", "已完成");
        jdbc.update("INSERT INTO wao_todo_task_day_event (member_id, task_id, occurred_at, business_date, event_type, before_snapshot, after_snapshot) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "wg", "t1", second.atTime(10, 0), second, "TASK_CHANGED", changed, done);
        jdbc.update("INSERT INTO wao_todo_task_day_event (member_id, task_id, occurred_at, business_date, event_type, before_snapshot, after_snapshot) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "wg", "t1", second.atTime(11, 0), second, "TASK_CHANGED", done, changed);
        jdbc.update("INSERT INTO wao_todo_task_day_event (member_id, task_id, occurred_at, business_date, event_type, before_snapshot, after_snapshot) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "wg", "t1", second.atTime(12, 0), second, "TASK_CHANGED", changed, done);
        jdbc.update("INSERT INTO wao_todo_task_day_event (member_id, task_id, occurred_at, business_date, event_type, before_snapshot, after_snapshot) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "wg", "t1", third.atTime(8, 0), third, "TASK_CHANGED", done, changed);
        TaskService tasks = mock(TaskService.class);
        TaskDto current = new ObjectMapper().readValue("{\"id\":\"t1\",\"title\":\"新标题\",\"project\":\"p1\",\"owner\":\"wg\"}", TaskDto.class);
        when(tasks.list("wg")).thenReturn(List.of(current));
        TodoDayService service = new TodoDayService(jdbc, new ObjectMapper(), tasks);

        TodoDayService.Day earlier = service.day(first, "wg");
        TodoDayService.Day changedDay = service.day(second, "wg");
        assertThat(earlier.tasks()).hasSize(1);
        assertThat(earlier.tasks().get(0).snapshot().path("title").asText()).isEqualTo("旧标题");
        assertThat(earlier.summary().plannedCount()).isEqualTo(1);
        assertThat(changedDay.tasks()).hasSize(1);
        assertThat(changedDay.tasks().get(0).planned()).isFalse();
        assertThat(changedDay.tasks().get(0).events()).hasSize(4);
        assertThat(changedDay.tasks().get(0).snapshot().path("title").asText()).isEqualTo("新标题");
        assertThat(changedDay.tasks().get(0).snapshot().path("status").asText()).isEqualTo("已完成");
        assertThat(changedDay.summary().finishedCount()).isEqualTo(1);
        assertThat(service.day(third, "wg").summary().finishedCount()).isZero();
        var month = service.month(first, third, "wg");
        assertThat(month.days()).extracting(TodoDayService.DaySummary::finishedCount).containsExactly(0, 1, 0);
    }
}
