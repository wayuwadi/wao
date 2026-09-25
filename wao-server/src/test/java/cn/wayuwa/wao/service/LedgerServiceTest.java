package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.ForbiddenException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.service.LedgerService.EntryInput;
import cn.wayuwa.wao.service.LedgerService.SettlementInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LedgerServiceTest {
    private LedgerService ledger;
    private JdbcTemplate db;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:ledger_" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE member(id VARCHAR(32) PRIMARY KEY)");
        db.execute("CREATE TABLE ledger_book(id VARCHAR(36) PRIMARY KEY,owner_member_id VARCHAR(32),title VARCHAR(128),created_at TIMESTAMP,updated_at TIMESTAMP)");
        db.execute("CREATE TABLE ledger_book_member(book_id VARCHAR(36),member_id VARCHAR(32),role VARCHAR(12),created_at TIMESTAMP,PRIMARY KEY(book_id,member_id))");
        db.execute("""
                CREATE TABLE ledger_entry(id VARCHAR(36) PRIMARY KEY,book_id VARCHAR(36),title VARCHAR(160),
                  counterparty VARCHAR(160),category VARCHAR(12),direction VARCHAR(12),status VARCHAR(16),
                  amount DECIMAL(15,2),occurred_on DATE,due_on DATE,evidence VARCHAR(1000),
                  dispute_note VARCHAR(1000),next_action VARCHAR(1000),created_by VARCHAR(32),
                  updated_by VARCHAR(32),version INT,created_at TIMESTAMP,updated_at TIMESTAMP)
                """);
        db.execute("""
                CREATE TABLE ledger_settlement(id VARCHAR(36) PRIMARY KEY,entry_id VARCHAR(36),
                  amount DECIMAL(15,2),settled_on DATE,note VARCHAR(500),created_by VARCHAR(32),created_at TIMESTAMP)
                """);
        db.execute("""
                CREATE TABLE ledger_entry_revision(id VARCHAR(36) PRIMARY KEY,entry_id VARCHAR(36),
                  before_json CLOB,after_json CLOB,changed_by VARCHAR(32),changed_at TIMESTAMP)
                """);
        db.update("INSERT INTO member(id) VALUES('wd'),('wg'),('outsider')");
        ledger = new LedgerService(db, new ObjectMapper().findAndRegisterModules());
    }

    private EntryInput entry(String status, String amount, int version) {
        return new EntryInput("约定款项", "兄弟", "OLD", "RECEIVE", status, new BigDecimal(amount),
                LocalDate.of(2026, 9, 19), LocalDate.of(2026, 10, 15),
                "双方聊天确认", "DISPUTED".equals(status) ? "金额待对账" : null, "月底复核", version);
    }

    @Test
    void sharedEditorsSeeTheSameBookButOutsiderCannotRead() {
        var book = ledger.createBook("wd", "兄弟明账");
        assertThat(ledger.listBooks("wg")).isEmpty();
        ledger.shareBook("wd", book.id(), "wg", "EDITOR");
        assertThat(ledger.listBooks("wg")).extracting(LedgerService.Book::id).containsExactly(book.id());
        var created = ledger.createEntry("wg", book.id(), entry("CONFIRMED", "1000.00", 0));
        assertThat(ledger.getBook("wd", book.id()).entries()).extracting(LedgerService.Entry::id)
                .containsExactly(created.id());
        assertThatThrownBy(() -> ledger.getBook("outsider", book.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> ledger.createEntry("outsider", book.id(), entry("CONFIRMED", "1", 0)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void viewerCannotChangeEntryAndOnlyOwnerCanShare() {
        var book = ledger.createBook("wd", "兄弟明账");
        ledger.shareBook("wd", book.id(), "wg", "VIEWER");
        assertThat(ledger.getBook("wg", book.id()).book().role()).isEqualTo("VIEWER");
        assertThatThrownBy(() -> ledger.createEntry("wg", book.id(), entry("TO_VERIFY", "1", 0)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> ledger.shareBook("wg", book.id(), "outsider", "EDITOR"))
                .isInstanceOf(ForbiddenException.class);
        ledger.revokeBookAccess("wd", book.id(), "wg");
        assertThatThrownBy(() -> ledger.getBook("wg", book.id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void settlementsAreAppendedAndOnlyConfirmedRemainingBalanceIsCounted() {
        var book = ledger.createBook("wd", "兄弟明账");
        var confirmed = ledger.createEntry("wd", book.id(), entry("CONFIRMED", "1000.00", 0));
        var disputed = ledger.createEntry("wd", book.id(), entry("DISPUTED", "500.00", 0));
        ledger.addSettlement("wd", book.id(), confirmed.id(),
                new SettlementInput(new BigDecimal("200.00"), LocalDate.of(2026, 9, 20), "转账"));
        var detail = ledger.getBook("wd", book.id());
        assertThat(detail.summary().receivable()).isEqualByComparingTo("800.00");
        assertThat(detail.summary().disputedCount()).isEqualTo(1);
        assertThat(detail.entries().stream().filter(item -> item.id().equals(confirmed.id())).findFirst().orElseThrow()
                .settlements()).hasSize(1);
        assertThatThrownBy(() -> ledger.addSettlement("wd", book.id(), confirmed.id(),
                new SettlementInput(new BigDecimal("801.00"), LocalDate.of(2026, 9, 21), "超额")))
                .isInstanceOf(BadRequestException.class);
        var current = ledger.getBook("wd", book.id()).entries().stream()
                .filter(item -> item.id().equals(disputed.id())).findFirst().orElseThrow();
        ledger.updateEntry("wd", book.id(), disputed.id(), new EntryInput(
                current.title(), current.counterparty(), current.category(), current.direction(), "VOID",
                current.amount(), current.occurredOn(), current.dueOn(), current.evidence(),
                current.disputeNote(), current.nextAction(), current.version()));
        assertThat(ledger.getBook("wd", book.id()).summary().disputedCount()).isZero();
    }

    @Test
    void staleVersionAndMissingConfirmationEvidenceCannotOverwrite() {
        var book = ledger.createBook("wd", "兄弟明账");
        assertThatThrownBy(() -> ledger.createEntry("wd", book.id(), new EntryInput(
                "事项", "对方", "OLD", "RECEIVE", "CONFIRMED", new BigDecimal("10"),
                null, null, null, null, null, null))).isInstanceOf(BadRequestException.class);
        var item = ledger.createEntry("wd", book.id(), entry("CONFIRMED", "1000.00", 0));
        ledger.updateEntry("wd", book.id(), item.id(), entry("CONFIRMED", "900.00", item.version()));
        assertThat(ledger.getBook("wd", book.id()).entries().get(0).revisions()).hasSize(1);
        assertThatThrownBy(() -> ledger.updateEntry("wd", book.id(), item.id(),
                entry("CONFIRMED", "800.00", item.version()))).isInstanceOf(ConflictException.class);
    }
}
