package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.Mockito.mock;

class DailyRoutineServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);
    private JdbcTemplate jdbc;
    private ObjectMapper json;
    private DailyRoutineService service;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:daily_routine_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE account (id VARCHAR(64) PRIMARY KEY)");
        jdbc.execute("INSERT INTO account (id) VALUES ('a1'), ('a2')");
        jdbc.execute("""
                CREATE TABLE wao_todo_operation_receipt (
                  account_id VARCHAR(64), operation_id CHAR(36), operation_type VARCHAR(32),
                  request_hash CHAR(64), result_json CLOB, created_at TIMESTAMP,
                  PRIMARY KEY (account_id, operation_id))
                """);
        jdbc.execute("""
                CREATE TABLE wao_todo_routine_template (
                  id CHAR(36) PRIMARY KEY, member_id VARCHAR(64), version INT,
                  created_at TIMESTAMP, updated_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE wao_todo_routine_revision (
                  id CHAR(36) PRIMARY KEY, template_id CHAR(36), member_id VARCHAR(64),
                  effective_from DATE, effective_to DATE, title VARCHAR(120), period VARCHAR(16),
                  weekdays_json CLOB, sort_order INT, active BOOLEAN, archived BOOLEAN,
                  created_at TIMESTAMP, UNIQUE (template_id, effective_from))
                """);
        jdbc.execute("""
                CREATE TABLE wao_todo_routine_occurrence (
                  template_id CHAR(36), member_id VARCHAR(64), business_date DATE,
                  revision_id CHAR(36), title VARCHAR(120), period VARCHAR(16), sort_order INT,
                  status VARCHAR(16), version INT, recorded_at TIMESTAMP,
                  PRIMARY KEY (template_id, business_date))
                """);
        json = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new DailyRoutineService(jdbc, json,
                Clock.fixed(Instant.parse("2026-10-03T02:00:00Z"), ZoneId.of("Asia/Shanghai")));
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @Test
    void newRuleAppliesTodayAndTomorrowEditCannotRewriteUnvisitedToday() {
        String id = uuid();
        var created = tx.execute(status -> service.putRule(id, uuid(),
                rule(null, "晨间回顾", "MORNING", true, false), "a1", "m1"));
        assertThat(created.effectiveFrom()).isEqualTo(TODAY);
        assertThat(created.template().version()).isEqualTo(1);
        var todayBefore = service.range(TODAY, TODAY.plusDays(1), "m1");
        assertThat(todayBefore.days().get(0).occurrences()).singleElement()
                .satisfies(o -> {
                    assertThat(o.title()).isEqualTo("晨间回顾");
                    assertThat(o.virtual()).isTrue();
                    assertThat(o.version()).isZero();
                });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wao_todo_routine_occurrence", Integer.class)).isZero();

        var changed = tx.execute(status -> service.putRule(id, uuid(),
                rule(1, "夜间整理", "EVENING", true, false), "a1", "m1"));
        assertThat(changed.effectiveFrom()).isEqualTo(TODAY.plusDays(1));
        var after = service.range(TODAY, TODAY.plusDays(1), "m1");
        assertThat(after.days().get(0).occurrences().get(0).title()).isEqualTo("晨间回顾");
        assertThat(after.days().get(1).occurrences().get(0).title()).isEqualTo("夜间整理");
        assertThat(changed.template().currentRevision().title()).isEqualTo("晨间回顾");
        assertThat(changed.template().nextRevision().title()).isEqualTo("夜间整理");

        tx.executeWithoutResult(status -> service.putRule(id, uuid(),
                rule(2, "夜间复盘", "EVENING", true, false), "a1", "m1"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wao_todo_routine_revision", Integer.class)).isEqualTo(2);
        assertThat(service.range(TODAY, TODAY.plusDays(1), "m1").days().get(0)
                .occurrences().get(0).title()).isEqualTo("晨间回顾");
        assertThat(service.range(TODAY, TODAY.plusDays(1), "m1").days().get(1)
                .occurrences().get(0).title()).isEqualTo("夜间复盘");
    }

    @Test
    void onlyActionPersistsOccurrenceAndReplayNeverAddsAnotherStatusTransition() {
        String id = uuid();
        tx.executeWithoutResult(status -> service.putRule(id, uuid(),
                rule(null, "晨间回顾", "MORNING", true, false), "a1", "m1"));
        String doneOp = uuid();
        var done = tx.execute(status -> service.putOccurrence(id, TODAY, doneOp,
                new DailyRoutineService.StatusRequest(0, "DONE"), "a1", "m1"));
        assertThat(done.occurrence().status()).isEqualTo("DONE");
        assertThat(done.occurrence().version()).isEqualTo(1);
        assertThat(done.occurrence().virtual()).isFalse();
        assertThat(service.receipt(doneOp, "a1", "m1").status()).isEqualTo("COMMITTED");

        tx.executeWithoutResult(status -> service.putOccurrence(id, TODAY, uuid(),
                new DailyRoutineService.StatusRequest(1, "SKIPPED"), "a1", "m1"));
        tx.executeWithoutResult(status -> service.putOccurrence(id, TODAY, uuid(),
                new DailyRoutineService.StatusRequest(2, "OPEN"), "a1", "m1"));
        assertThat(service.range(TODAY, TODAY, "m1").days().get(0).occurrences().get(0).status())
                .isEqualTo("OPEN");
        assertThat(service.putOccurrence(id, TODAY, doneOp,
                new DailyRoutineService.StatusRequest(0, "DONE"), "a1", "m1")
                .occurrence().version()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wao_todo_routine_occurrence", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wao_todo_operation_receipt", Integer.class)).isEqualTo(4);
        assertThatThrownBy(() -> service.putOccurrence(id, TODAY, doneOp,
                new DailyRoutineService.StatusRequest(3, "DONE"), "a1", "m1"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.putOccurrence(id, TODAY, uuid(),
                new DailyRoutineService.StatusRequest(1, "DONE"), "a1", "m1"))
                .isInstanceOf(ConflictException.class);
        assertThat(service.receipt(doneOp, "a2", "m2").status()).isEqualTo("UNCONFIRMED");
        assertThat(service.receipt(doneOp, "a1", "m2").status()).isEqualTo("UNCONFIRMED");
        DailyRoutineService tomorrow = new DailyRoutineService(jdbc, json,
                Clock.fixed(Instant.parse("2026-10-04T02:00:00Z"), ZoneId.of("Asia/Shanghai")));
        assertThat(tomorrow.putOccurrence(id, TODAY, doneOp,
                new DailyRoutineService.StatusRequest(0, "DONE"), "a1", "m1")
                .occurrence().version()).isEqualTo(3);
        assertThatThrownBy(() -> tomorrow.putOccurrence(id, TODAY, uuid(),
                new DailyRoutineService.StatusRequest(3, "DONE"), "a1", "m1"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void ruleReceiptReplaysAfterFutureEditsWithoutRepeatingMutation() {
        String id = uuid();
        String createOp = uuid();
        var request = rule(null, "晨间回顾", "MORNING", true, false);
        tx.executeWithoutResult(status -> service.putRule(id, createOp, request, "a1", "m1"));
        tx.executeWithoutResult(status -> service.putRule(id, uuid(),
                rule(1, "夜间整理", "EVENING", true, false), "a1", "m1"));
        var replay = service.putRule(id, createOp, request, "a1", "m1");
        assertThat(replay.effectiveFrom()).isEqualTo(TODAY);
        assertThat(replay.template().version()).isEqualTo(2);
        assertThat(service.receipt(createOp, "a1", "m1").status()).isEqualTo("COMMITTED");
        assertThat(service.receipt(createOp, "a1", "m2").status()).isEqualTo("UNCONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wao_todo_routine_revision", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> service.putRule(id, createOp,
                rule(null, "不同内容", "MORNING", true, false), "a1", "m1"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void sharedOperationLookupDispatchesRoutineReceiptAndRechecksCurrentMember() {
        String id = uuid();
        String operation = uuid();
        tx.executeWithoutResult(status -> service.putRule(id, operation,
                rule(null, "晨间回顾", "MORNING", true, false), "a1", "m1"));
        var shared = new TodoOperationService(jdbc, json, mock(TaskMapper.class), mock(DocMapper.class),
                mock(TaskService.class), mock(KnowledgeService.class), mock(TodoHistoryService.class),
                mock(NoteAiWorkflowService.class), service);
        var committed = shared.receipt(operation, "a1", "m1");
        assertThat(committed.status()).isEqualTo("COMMITTED");
        assertThat(committed.type()).isEqualTo("ROUTINE_RULE");
        assertThat(((DailyRoutineService.RuleResult) committed.result()).template().id()).isEqualTo(id);
        assertThat(shared.receipt(operation, "a1", "m2").status()).isEqualTo("UNCONFIRMED");
        assertThat(shared.receipt(uuid(), "a1", "m1").status()).isEqualTo("UNCONFIRMED");
    }

    @Test
    void pauseArchiveAndWeekdaysDoNotGenerateBacklogOrExposeOtherMember() {
        String id = uuid();
        var weekdays = new DailyRoutineService.RuleRequest(null, "工作日", "MORNING",
                List.of(1, 2, 3, 4, 5), 10, true, false);
        tx.executeWithoutResult(status -> service.putRule(id, uuid(), weekdays, "a1", "m1"));
        assertThat(service.range(TODAY, TODAY.plusDays(2), "m1").days().get(0).occurrences()).isEmpty();
        assertThat(service.range(TODAY, TODAY.plusDays(2), "m1").days().get(2).occurrences())
                .singleElement();
        tx.executeWithoutResult(status -> service.putRule(id, uuid(),
                new DailyRoutineService.RuleRequest(1, "工作日", "MORNING",
                        List.of(1, 2, 3, 4, 5), 10, false, false), "a1", "m1"));
        assertThat(service.range(TODAY, TODAY.plusDays(2), "m1").days().get(2).occurrences()).isEmpty();
        assertThat(service.range(TODAY, TODAY.plusDays(2), "m2").templates()).isEmpty();
        assertThatThrownBy(() -> service.get(id, "m2")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.putRule(id, uuid(), rule(null, "偷看", "EVENING", true, false),
                "a2", "m2")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.putOccurrence(id, TODAY.minusDays(1), uuid(),
                new DailyRoutineService.StatusRequest(0, "DONE"), "a1", "m1"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.putOccurrence(id, TODAY.plusDays(1), uuid(),
                new DailyRoutineService.StatusRequest(0, "DONE"), "a1", "m1"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void archiveTomorrowKeepsTodaysStatusAndSnapshotWhileMorningSortsFirst() {
        String morning = uuid();
        String evening = uuid();
        tx.executeWithoutResult(status -> service.putRule(evening, uuid(),
                rule(null, "夜间整理", "EVENING", true, false), "a1", "m1"));
        tx.executeWithoutResult(status -> service.putRule(morning, uuid(),
                rule(null, "晨间回顾", "MORNING", true, false), "a1", "m1"));
        assertThat(service.range(TODAY, TODAY, "m1").days().get(0).occurrences())
                .extracting(DailyRoutineService.Occurrence::period)
                .containsExactly("MORNING", "EVENING");
        tx.executeWithoutResult(status -> service.putOccurrence(morning, TODAY, uuid(),
                new DailyRoutineService.StatusRequest(0, "DONE"), "a1", "m1"));
        tx.executeWithoutResult(status -> service.putRule(morning, uuid(),
                rule(1, "更名并归档", "EVENING", true, true), "a1", "m1"));
        var result = service.range(TODAY, TODAY.plusDays(1), "m1");
        assertThat(result.templates().stream().filter(template -> template.id().equals(morning))
                .findFirst().orElseThrow().nextRevision().active()).isFalse();
        assertThat(result.days().get(0).occurrences().get(0).title()).isEqualTo("晨间回顾");
        assertThat(result.days().get(0).occurrences().get(0).status()).isEqualTo("DONE");
        assertThat(result.days().get(1).occurrences())
                .extracting(DailyRoutineService.Occurrence::templateId).doesNotContain(morning);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wao_todo_routine_occurrence", Integer.class)).isEqualTo(1);
    }

    private static DailyRoutineService.RuleRequest rule(Integer version, String title, String period,
            boolean active, boolean archived) {
        return new DailyRoutineService.RuleRequest(version, title, period, List.of(6, 7), 10, active, archived);
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }
}
