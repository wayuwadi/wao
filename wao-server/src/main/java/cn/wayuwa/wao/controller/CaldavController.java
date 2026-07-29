package cn.wayuwa.wao.controller;

import java.time.LocalDate;
import java.util.List;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.dto.CaldavAccountDto;
import cn.wayuwa.wao.dto.CaldavCalendarDto;
import cn.wayuwa.wao.dto.CaldavEventDto;
import cn.wayuwa.wao.dto.CreateCaldavAccountRequest;
import cn.wayuwa.wao.dto.PatchCaldavCalendarRequest;
import cn.wayuwa.wao.service.CaldavService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CalDAV 订阅接口。账户、日历与事件都按当前登录成员隔离，凭据从不在任何响应中出现。
 */
@RestController
@RequestMapping("/api/caldav")
public class CaldavController {

    /** 关掉登录保护的本地 / dev 环境只有一个身份，按管理员处理，与其他成员级模块一致。 */
    private static final String FALLBACK_OWNER = "wg";

    private final CaldavService caldavService;
    private final CurrentMember currentMember;

    public CaldavController(CaldavService caldavService, CurrentMember currentMember) {
        this.caldavService = caldavService;
        this.currentMember = currentMember;
    }

    private String owner(HttpServletRequest http) {
        return currentMember.idOr(http, FALLBACK_OWNER);
    }

    @GetMapping("/accounts")
    public ApiResponse<List<CaldavAccountDto>> listAccounts(HttpServletRequest http) {
        return ApiResponse.ok(caldavService.listAccounts(owner(http)));
    }

    @PostMapping("/accounts")
    public ApiResponse<CaldavAccountDto> addAccount(@Valid @RequestBody CreateCaldavAccountRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(caldavService.addAccount(request, owner(http)));
    }

    @DeleteMapping("/accounts/{id}")
    public ApiResponse<Void> deleteAccount(@PathVariable String id, HttpServletRequest http) {
        caldavService.deleteAccount(id, owner(http));
        return ApiResponse.ok(null);
    }

    @PostMapping("/accounts/{id}/sync")
    public ApiResponse<CaldavAccountDto> syncNow(@PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(caldavService.syncNow(id, owner(http)));
    }

    @PatchMapping("/calendars/{id}")
    public ApiResponse<CaldavCalendarDto> toggleCalendar(@PathVariable String id,
            @RequestBody PatchCaldavCalendarRequest request, HttpServletRequest http) {
        return ApiResponse.ok(caldavService.toggleCalendar(id, request, owner(http)));
    }

    @GetMapping("/events")
    public ApiResponse<List<CaldavEventDto>> events(@RequestParam String from, @RequestParam String to,
            HttpServletRequest http) {
        LocalDate f;
        LocalDate t;
        try {
            f = LocalDate.parse(from);
            t = LocalDate.parse(to);
        } catch (Exception e) {
            throw new BadRequestException("日期格式应为 yyyy-MM-dd");
        }
        if (t.isBefore(f)) throw new BadRequestException("to 不能早于 from");
        if (t.isAfter(f.plusDays(370))) throw new BadRequestException("查询区间过长");
        return ApiResponse.ok(caldavService.events(owner(http), f, t));
    }
}
