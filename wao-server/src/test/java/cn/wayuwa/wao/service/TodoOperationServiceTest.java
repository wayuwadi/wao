package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.RichDocContentDto;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class TodoOperationServiceTest {
    @Test
    void lostNoteResponseReplaysOneCanonicalDocAndOneDayContext() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:todo_note_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE account (id VARCHAR(64) PRIMARY KEY)");
        jdbc.execute("INSERT INTO account (id) VALUES ('a1')");
        jdbc.execute("""
                CREATE TABLE wao_todo_operation_receipt (
                  account_id VARCHAR(64), operation_id CHAR(36), operation_type VARCHAR(32),
                  request_hash CHAR(64), result_json CLOB, created_at TIMESTAMP,
                  PRIMARY KEY (account_id, operation_id))
                """);
        jdbc.execute("""
                CREATE TABLE wao_todo_day_note_context (
                  doc_id VARCHAR(32) PRIMARY KEY, member_id VARCHAR(64), business_date DATE,
                  created_at TIMESTAMP, late BOOLEAN, task_id VARCHAR(64))
                """);
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        KnowledgeService knowledge = mock(KnowledgeService.class);
        DocMapper docMapper = mock(DocMapper.class);
        DocEntity persisted = new DocEntity();
        persisted.setId("d-stable");
        persisted.setKbId(KnowledgeService.NOTES_KB_ID);
        persisted.setAuthorId("wg");
        when(docMapper.selectById("d-stable")).thenReturn(null, persisted);
        NoteAiWorkflowService noteAi = mock(NoteAiWorkflowService.class);
        DocDto canonical = json.readValue("{\"id\":\"d-stable\",\"title\":\"进展\",\"rev\":1}", DocDto.class);
        when(knowledge.createNote(eq("d-stable"), any(), eq("wg"))).thenReturn(canonical);
        when(knowledge.getDoc("d-stable")).thenReturn(canonical);
        TodoOperationService service = new TodoOperationService(jdbc, json, mock(TaskMapper.class), docMapper,
                mock(TaskService.class), knowledge, mock(TodoHistoryService.class), noteAi);
        String operation = UUID.randomUUID().toString();
        var request = new TodoOperationService.NoteRequest("d-stable", null, null,
                LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(1), null,
                "进展", null, new RichDocContentDto(1, null, "今天推进了步骤"), List.of(),
                false, false, false);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        var saved = tx.execute(status -> service.note(operation, request, "a1", "wg"));
        var replay = tx.execute(status -> service.note(operation, request, "a1", "wg"));
        assertThat(saved.doc().id()).isEqualTo("d-stable");
        assertThat(replay.doc().id()).isEqualTo("d-stable");
        assertThat(saved.late()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wao_todo_day_note_context", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wao_todo_operation_receipt", Integer.class)).isEqualTo(1);
        verify(knowledge, times(1)).createNote(eq("d-stable"), any(), eq("wg"));
        verify(noteAi, times(1)).beginIfRequested(eq("wg"), eq(canonical), any());
    }

    @Test
    void scheduleReceiptReplaysOnlyOriginalRequestAndNeverChangesDue() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:todo_receipt_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE account (id VARCHAR(64) PRIMARY KEY)");
        jdbc.execute("INSERT INTO account (id) VALUES ('a1')");
        jdbc.execute("""
                CREATE TABLE wao_todo_operation_receipt (
                  account_id VARCHAR(64), operation_id CHAR(36), operation_type VARCHAR(32),
                  request_hash CHAR(64), result_json CLOB, created_at TIMESTAMP,
                  PRIMARY KEY (account_id, operation_id))
                """);
        ObjectMapper json = new ObjectMapper();
        TaskMapper mapper = mock(TaskMapper.class);
        TaskService tasks = mock(TaskService.class);
        TodoHistoryService history = mock(TodoHistoryService.class);
        TaskEntity entity = new TaskEntity();
        entity.setId("t1");
        entity.setProjectId("p1");
        entity.setOwnerId("wg");
        entity.setDueDate("2026-10-10");
        entity.setDetailJson("{}");
        when(mapper.lockById("t1")).thenReturn(entity);
        when(history.copy(entity)).thenReturn(entity);
        TaskDto before = json.readValue("{\"id\":\"t1\",\"editVersion\":\"v1\",\"due\":\"2026-10-10\"}", TaskDto.class);
        TaskDto after = json.readValue("{\"id\":\"t1\",\"editVersion\":\"v2\",\"due\":\"2026-10-10\",\"start\":\"2026-10-03\",\"schedulePeriod\":\"EVENING\"}", TaskDto.class);
        when(tasks.get("t1", "wg")).thenReturn(before, before, after, after);
        TodoOperationService service = new TodoOperationService(jdbc, json, mapper, mock(DocMapper.class),
                tasks, mock(KnowledgeService.class), history, mock(NoteAiWorkflowService.class));
        String operation = UUID.randomUUID().toString();
        var request = new TodoOperationService.ScheduleRequest("v1", "2026-10-03", null, null, "EVENING");

        var saved = service.schedule("t1", operation, request, "a1", "wg");
        var replay = service.schedule("t1", operation, request, "a1", "wg");
        assertThat(saved.task().due()).isEqualTo("2026-10-10");
        assertThat(replay.operationId()).isEqualTo(saved.operationId());
        assertThat(service.receipt(operation, "a1", "wg").status()).isEqualTo("COMMITTED");
        assertThat(service.receipt(UUID.randomUUID().toString(), "a1", "wg").status()).isEqualTo("UNCONFIRMED");
        assertThatThrownBy(() -> service.schedule("t1", operation,
                new TodoOperationService.ScheduleRequest("v1", "2026-10-04", null, null, "EVENING"), "a1", "wg"))
                .isInstanceOf(ConflictException.class);
        verify(mapper, times(1)).updateSchedule(eq("t1"), eq("2026-10-03"), any());
        verify(history, times(1)).record(any(), any(), eq("wg"), eq("SCHEDULE_CHANGED"), eq(operation));
        assertThat(entity.getDueDate()).isEqualTo("2026-10-10");
    }
}
