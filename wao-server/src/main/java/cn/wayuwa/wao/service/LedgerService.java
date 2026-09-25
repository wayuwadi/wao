package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.ForbiddenException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.UnauthorizedException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 按账本成员隔离的明账服务；金额用十进制计算，结算只追加并保留来源。 */
@Service
public class LedgerService {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");
    private static final Set<String> CATEGORIES = Set.of("OLD", "BAD", "FUTURE");
    private static final Set<String> DIRECTIONS = Set.of("RECEIVE", "PAY");
    private static final Set<String> STATUSES = Set.of("TO_VERIFY", "CONFIRMED", "DISPUTED", "VOID");
    private static final Set<String> ROLES = Set.of("EDITOR", "VIEWER");

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public LedgerService(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public record Book(String id, String title, String ownerMemberId, String role, LocalDateTime updatedAt) {}
    public record MemberAccess(String memberId, String role) {}
    public record Settlement(String id, BigDecimal amount, LocalDate settledOn, String note,
                             String createdBy, LocalDateTime createdAt) {}
    public record EntrySnapshot(String title, String counterparty, String category, String direction,
                                String status, BigDecimal amount, LocalDate occurredOn, LocalDate dueOn,
                                String evidence, String disputeNote, String nextAction) {}
    public record EntryRevision(String id, EntrySnapshot before, EntrySnapshot after,
                                String changedBy, LocalDateTime changedAt) {}
    public record Entry(String id, String bookId, String title, String counterparty, String category,
                        String direction, String status, BigDecimal amount, BigDecimal settledAmount,
                        BigDecimal remainingAmount, LocalDate occurredOn, LocalDate dueOn, String evidence,
                        String disputeNote, String nextAction, String createdBy, String updatedBy,
                        int version, LocalDateTime updatedAt, List<Settlement> settlements,
                        List<EntryRevision> revisions) {}
    public record Summary(BigDecimal receivable, BigDecimal payable, BigDecimal net,
                          int disputedCount, int toVerifyCount, int undatedConfirmedCount) {}
    public record BookDetail(Book book, List<MemberAccess> members, List<Entry> entries, Summary summary) {}
    public record EntryInput(String title, String counterparty, String category, String direction,
                             String status, BigDecimal amount, LocalDate occurredOn, LocalDate dueOn,
                             String evidence, String disputeNote, String nextAction, Integer version) {}
    public record SettlementInput(BigDecimal amount, LocalDate settledOn, String note) {}

    private static final RowMapper<Book> BOOK_ROW = (rs, row) -> new Book(
            rs.getString("id"), rs.getString("title"), rs.getString("owner_member_id"),
            rs.getString("role"), rs.getTimestamp("updated_at").toLocalDateTime());

    /** 列出当前登录成员创建或受邀加入的账本。 */
    public List<Book> listBooks(String memberId) {
        requireLogin(memberId);
        return db.query("""
                SELECT b.id,b.title,b.owner_member_id,b.updated_at,
                       CASE WHEN b.owner_member_id=? THEN 'OWNER' ELSE m.role END AS role
                FROM ledger_book b LEFT JOIN ledger_book_member m
                  ON m.book_id=b.id AND m.member_id=?
                WHERE b.owner_member_id=? OR m.member_id=?
                ORDER BY b.updated_at DESC
                """, BOOK_ROW, memberId, memberId, memberId, memberId);
    }

    /** 创建默认私人的账本；共享只能由创建者后续明确指定。 */
    public Book createBook(String memberId, String title) {
        requireLogin(memberId);
        String cleanTitle = required(title, 128, "账本名称");
        String id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        db.update("INSERT INTO ledger_book(id,owner_member_id,title,created_at,updated_at) VALUES(?,?,?,?,?)",
                id, memberId, cleanTitle, now, now);
        return new Book(id, cleanTitle, memberId, "OWNER", now);
    }

    /** 读取账本的成员、明账、结算和口径明确的汇总。 */
    public BookDetail getBook(String memberId, String bookId) {
        Book book = requireBook(memberId, bookId);
        List<MemberAccess> members = db.query("""
                SELECT member_id,role FROM ledger_book_member WHERE book_id=? ORDER BY created_at
                """, (rs, row) -> new MemberAccess(rs.getString(1), rs.getString(2)), bookId);
        List<Entry> entries = db.query("""
                SELECT e.*,(SELECT COALESCE(SUM(s.amount),0) FROM ledger_settlement s WHERE s.entry_id=e.id)
                  AS settled_amount
                FROM ledger_entry e WHERE e.book_id=? ORDER BY e.updated_at DESC,e.id DESC
                """, (rs, row) -> entryRow(rs), bookId);
        BigDecimal receive = BigDecimal.ZERO, pay = BigDecimal.ZERO;
        int disputed = 0, toVerify = 0, undated = 0;
        for (Entry entry : entries) {
            if ("DISPUTED".equals(entry.status())) disputed++;
            if ("TO_VERIFY".equals(entry.status())) toVerify++;
            if (!"CONFIRMED".equals(entry.status())) continue;
            if (entry.dueOn() == null && entry.remainingAmount().signum() > 0) undated++;
            if ("RECEIVE".equals(entry.direction())) receive = receive.add(entry.remainingAmount());
            else pay = pay.add(entry.remainingAmount());
        }
        return new BookDetail(book, members, entries,
                new Summary(receive, pay, receive.subtract(pay), disputed, toVerify, undated));
    }

    /** 只有创建者可邀请已有 WAO 成员；受邀成员可编辑或只读。 */
    public void shareBook(String memberId, String bookId, String targetMemberId, String role) {
        Book book = requireBook(memberId, bookId);
        if (!book.ownerMemberId().equals(memberId)) throw new ForbiddenException("只有账本创建者可以管理共享成员");
        if (!ROLES.contains(role)) throw new BadRequestException("共享角色不正确");
        if (targetMemberId == null || targetMemberId.isBlank() || targetMemberId.equals(memberId)) {
            throw new BadRequestException("请选择其他 WAO 成员");
        }
        Integer exists = db.queryForObject("SELECT COUNT(*) FROM member WHERE id=?", Integer.class, targetMemberId);
        if (exists == null || exists == 0) throw new BadRequestException("共享成员不存在");
        db.update("""
                INSERT INTO ledger_book_member(book_id,member_id,role,created_at) VALUES(?,?,?,?)
                ON DUPLICATE KEY UPDATE role=VALUES(role)
                """, bookId, targetMemberId, role, LocalDateTime.now());
    }

    /** 创建者可撤销受邀成员访问；账目与结算历史不受影响。 */
    public void revokeBookAccess(String memberId, String bookId, String targetMemberId) {
        Book book = requireBook(memberId, bookId);
        if (!book.ownerMemberId().equals(memberId)) throw new ForbiddenException("只有账本创建者可以管理共享成员");
        db.update("DELETE FROM ledger_book_member WHERE book_id=? AND member_id=?", bookId, targetMemberId);
    }

    /** 创建一笔明账；私下事项由用户约定不录入，不提供隐性分类。 */
    @Transactional
    public Entry createEntry(String memberId, String bookId, EntryInput input) {
        requireWrite(memberId, bookId);
        validate(input);
        String id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        db.update("""
                INSERT INTO ledger_entry(id,book_id,title,counterparty,category,direction,status,amount,
                  occurred_on,due_on,evidence,dispute_note,next_action,created_by,updated_by,version,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?)
                """, id, bookId, input.title().trim(), input.counterparty().trim(), input.category(),
                input.direction(), input.status(), input.amount(), input.occurredOn(), input.dueOn(),
                blank(input.evidence()), blank(input.disputeNote()), blank(input.nextAction()),
                memberId, memberId, now, now);
        touchBook(bookId);
        return requireEntry(bookId, id);
    }

    /** 比较版本后修改一笔明账；冲突时不覆盖另一设备的内容。 */
    @Transactional
    public Entry updateEntry(String memberId, String bookId, String entryId, EntryInput input) {
        requireWrite(memberId, bookId);
        validate(input);
        if (input.version() == null || input.version() < 1) throw new BadRequestException("缺少账目版本");
        List<String> locked = db.query("SELECT id FROM ledger_entry WHERE id=? AND book_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), entryId, bookId);
        if (locked.isEmpty()) throw new NotFoundException("账目不存在");
        Entry previous = requireEntry(bookId, entryId);
        if (input.amount().compareTo(previous.settledAmount()) < 0) {
            throw new BadRequestException("原始金额不能低于已结算金额");
        }
        int changed = db.update("""
                UPDATE ledger_entry SET title=?,counterparty=?,category=?,direction=?,status=?,amount=?,
                  occurred_on=?,due_on=?,evidence=?,dispute_note=?,next_action=?,updated_by=?,
                  version=version+1,updated_at=? WHERE id=? AND book_id=? AND version=?
                """, input.title().trim(), input.counterparty().trim(), input.category(), input.direction(),
                input.status(), input.amount(), input.occurredOn(), input.dueOn(), blank(input.evidence()),
                blank(input.disputeNote()), blank(input.nextAction()), memberId, LocalDateTime.now(),
                entryId, bookId, input.version());
        if (changed != 1) throw new ConflictException("账目已被另一人修改，请刷新后核对");
        db.update("""
                INSERT INTO ledger_entry_revision(id,entry_id,before_json,after_json,changed_by,changed_at)
                VALUES(?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), entryId, writeSnapshot(snapshot(previous)),
                writeSnapshot(snapshot(input)), memberId, LocalDateTime.now());
        touchBook(bookId);
        return requireEntry(bookId, entryId);
    }

    /** 追加一次真实结算，不修改历史结算；数据库行锁防止双人同时超额入账。 */
    @Transactional
    public Entry addSettlement(String memberId, String bookId, String entryId, SettlementInput input) {
        requireWrite(memberId, bookId);
        if (input == null) throw new BadRequestException("缺少结算内容");
        money(input.amount());
        if (input.settledOn() == null) throw new BadRequestException("请填写结算日期");
        limited(input.note(), 500, "结算说明");
        List<BigDecimal> locked = db.query("SELECT amount FROM ledger_entry WHERE id=? AND book_id=? FOR UPDATE",
                (rs, row) -> rs.getBigDecimal(1), entryId, bookId);
        if (locked.isEmpty()) throw new NotFoundException("账目不存在");
        Entry previous = requireEntry(bookId, entryId);
        if ("VOID".equals(previous.status())) throw new BadRequestException("已作废账目不能追加结算");
        if (input.amount().compareTo(previous.remainingAmount()) > 0) {
            throw new BadRequestException("结算金额不能超过未结金额");
        }
        db.update("""
                INSERT INTO ledger_settlement(id,entry_id,amount,settled_on,note,created_by,created_at)
                VALUES(?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), entryId, input.amount(), input.settledOn(),
                blank(input.note()), memberId, LocalDateTime.now());
        db.update("UPDATE ledger_entry SET version=version+1,updated_by=?,updated_at=? WHERE id=?",
                memberId, LocalDateTime.now(), entryId);
        touchBook(bookId);
        return requireEntry(bookId, entryId);
    }

    private Book requireBook(String memberId, String bookId) {
        requireLogin(memberId);
        List<Book> rows = db.query("""
                SELECT b.id,b.title,b.owner_member_id,b.updated_at,
                       CASE WHEN b.owner_member_id=? THEN 'OWNER' ELSE m.role END AS role
                FROM ledger_book b LEFT JOIN ledger_book_member m
                  ON m.book_id=b.id AND m.member_id=?
                WHERE b.id=? AND (b.owner_member_id=? OR m.member_id=?)
                """, BOOK_ROW, memberId, memberId, bookId, memberId, memberId);
        if (rows.isEmpty()) throw new NotFoundException("账本不存在");
        return rows.get(0);
    }

    private void requireWrite(String memberId, String bookId) {
        Book book = requireBook(memberId, bookId);
        if ("VIEWER".equals(book.role())) throw new ForbiddenException("该账本仅可查看");
    }

    private Entry requireEntry(String bookId, String entryId) {
        List<Entry> rows = db.query("""
                SELECT e.*,(SELECT COALESCE(SUM(s.amount),0) FROM ledger_settlement s WHERE s.entry_id=e.id)
                  AS settled_amount FROM ledger_entry e WHERE e.id=? AND e.book_id=?
                """, (rs, row) -> entryRow(rs), entryId, bookId);
        if (rows.isEmpty()) throw new NotFoundException("账目不存在");
        return rows.get(0);
    }

    private Entry entryRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        String id = rs.getString("id");
        BigDecimal amount = rs.getBigDecimal("amount");
        BigDecimal settled = rs.getBigDecimal("settled_amount");
        List<Settlement> settlements = db.query("""
                SELECT id,amount,settled_on,note,created_by,created_at
                FROM ledger_settlement WHERE entry_id=? ORDER BY settled_on,created_at,id
                """, (s, row) -> new Settlement(s.getString("id"), s.getBigDecimal("amount"),
                date(s, "settled_on"), s.getString("note"), s.getString("created_by"),
                s.getTimestamp("created_at").toLocalDateTime()), id);
        List<EntryRevision> revisions = db.query("""
                SELECT id,before_json,after_json,changed_by,changed_at
                FROM ledger_entry_revision WHERE entry_id=? ORDER BY changed_at,id
                """, (change, row) -> new EntryRevision(change.getString("id"),
                readSnapshot(change.getString("before_json")), readSnapshot(change.getString("after_json")),
                change.getString("changed_by"), change.getTimestamp("changed_at").toLocalDateTime()), id);
        return new Entry(id, rs.getString("book_id"), rs.getString("title"), rs.getString("counterparty"),
                rs.getString("category"), rs.getString("direction"), rs.getString("status"), amount,
                settled, amount.subtract(settled), date(rs, "occurred_on"), date(rs, "due_on"),
                rs.getString("evidence"), rs.getString("dispute_note"), rs.getString("next_action"),
                rs.getString("created_by"), rs.getString("updated_by"), rs.getInt("version"),
                rs.getTimestamp("updated_at").toLocalDateTime(), settlements, revisions);
    }

    private static EntrySnapshot snapshot(Entry entry) {
        return new EntrySnapshot(entry.title(), entry.counterparty(), entry.category(), entry.direction(),
                entry.status(), entry.amount(), entry.occurredOn(), entry.dueOn(), entry.evidence(),
                entry.disputeNote(), entry.nextAction());
    }

    private static EntrySnapshot snapshot(EntryInput input) {
        return new EntrySnapshot(input.title().trim(), input.counterparty().trim(), input.category(),
                input.direction(), input.status(), input.amount(), input.occurredOn(), input.dueOn(),
                blank(input.evidence()), blank(input.disputeNote()), blank(input.nextAction()));
    }

    private String writeSnapshot(EntrySnapshot value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("账目历史无法保存", exception); }
    }

    private EntrySnapshot readSnapshot(String value) {
        try { return json.readValue(value, EntrySnapshot.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("账目历史无法读取", exception); }
    }

    private static LocalDate date(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private void touchBook(String bookId) {
        db.update("UPDATE ledger_book SET updated_at=? WHERE id=?", LocalDateTime.now(), bookId);
    }

    private static void requireLogin(String memberId) {
        if (memberId == null || memberId.isBlank()) throw new UnauthorizedException("请登录后使用账务本");
    }

    private static String required(String value, int max, String label) {
        if (value == null || value.isBlank()) throw new BadRequestException(label + "不能为空");
        if (value.trim().length() > max) throw new BadRequestException(label + "太长");
        return value.trim();
    }

    private static void limited(String value, int max, String label) {
        if (value != null && value.length() > max) throw new BadRequestException(label + "太长");
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void money(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.scale() > 2 || value.compareTo(MAX_AMOUNT) > 0) {
            throw new BadRequestException("金额须为正数，最多两位小数");
        }
    }

    private static void validate(EntryInput input) {
        if (input == null) throw new BadRequestException("缺少账目内容");
        required(input.title(), 160, "事项");
        required(input.counterparty(), 160, "对方");
        if (!CATEGORIES.contains(input.category())) throw new BadRequestException("账目归类不正确");
        if (!DIRECTIONS.contains(input.direction())) throw new BadRequestException("收付方向不正确");
        if (!STATUSES.contains(input.status())) throw new BadRequestException("核实状态不正确");
        money(input.amount());
        limited(input.evidence(), 1000, "确认依据");
        limited(input.disputeNote(), 1000, "争议说明");
        limited(input.nextAction(), 1000, "下一步约定");
        if ("CONFIRMED".equals(input.status()) && (input.evidence() == null || input.evidence().isBlank())) {
            throw new BadRequestException("已确认账目需要填写确认依据");
        }
        if ("DISPUTED".equals(input.status()) && (input.disputeNote() == null || input.disputeNote().isBlank())) {
            throw new BadRequestException("有争议账目需要填写争议说明");
        }
    }
}
