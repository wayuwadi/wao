package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.service.LedgerService;
import cn.wayuwa.wao.service.LedgerService.Book;
import cn.wayuwa.wao.service.LedgerService.BookDetail;
import cn.wayuwa.wao.service.LedgerService.Entry;
import cn.wayuwa.wao.service.LedgerService.EntryInput;
import cn.wayuwa.wao.service.LedgerService.SettlementInput;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 账务本浏览器接口；成员身份只从 WAO 服务端会话读取。 */
@RestController
@RequestMapping("/api/ledger")
public class LedgerController {
    public record CreateBook(String title) {}
    public record ShareBook(String role) {}

    private final LedgerService ledger;
    private final CurrentMember current;

    public LedgerController(LedgerService ledger, CurrentMember current) {
        this.ledger = ledger;
        this.current = current;
    }

    @GetMapping("/books")
    public ApiResponse<List<Book>> books(HttpServletRequest request) {
        return ApiResponse.ok(ledger.listBooks(current.id(request)));
    }

    @PostMapping("/books")
    public ApiResponse<Book> create(@RequestBody CreateBook body, HttpServletRequest request) {
        return ApiResponse.ok(ledger.createBook(current.id(request), body == null ? null : body.title()));
    }

    @GetMapping("/books/{bookId}")
    public ApiResponse<BookDetail> detail(@PathVariable String bookId, HttpServletRequest request) {
        return ApiResponse.ok(ledger.getBook(current.id(request), bookId));
    }

    @PutMapping("/books/{bookId}/members/{memberId}")
    public ApiResponse<Void> share(@PathVariable String bookId, @PathVariable String memberId,
            @RequestBody ShareBook body, HttpServletRequest request) {
        ledger.shareBook(current.id(request), bookId, memberId, body == null ? null : body.role());
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/books/{bookId}/members/{memberId}")
    public ApiResponse<Void> revoke(@PathVariable String bookId, @PathVariable String memberId,
            HttpServletRequest request) {
        ledger.revokeBookAccess(current.id(request), bookId, memberId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/books/{bookId}/entries")
    public ApiResponse<Entry> addEntry(@PathVariable String bookId, @RequestBody EntryInput body,
            HttpServletRequest request) {
        return ApiResponse.ok(ledger.createEntry(current.id(request), bookId, body));
    }

    @PatchMapping("/books/{bookId}/entries/{entryId}")
    public ApiResponse<Entry> editEntry(@PathVariable String bookId, @PathVariable String entryId,
            @RequestBody EntryInput body, HttpServletRequest request) {
        return ApiResponse.ok(ledger.updateEntry(current.id(request), bookId, entryId, body));
    }

    @PostMapping("/books/{bookId}/entries/{entryId}/settlements")
    public ApiResponse<Entry> settle(@PathVariable String bookId, @PathVariable String entryId,
            @RequestBody SettlementInput body, HttpServletRequest request) {
        return ApiResponse.ok(ledger.addSettlement(current.id(request), bookId, entryId, body));
    }
}
