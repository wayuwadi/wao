package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.CreateAccountRequest;
import cn.wayuwa.wao.dto.MemberDto;
import cn.wayuwa.wao.dto.OrgDto;
import cn.wayuwa.wao.dto.ResetMemberPasswordRequest;
import cn.wayuwa.wao.service.AccountAuthService;
import cn.wayuwa.wao.service.MemberService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 组织与成员接口。 */
@RestController
@RequestMapping("/api")
public class MemberController {

    /** 关掉登录保护的本地 / dev 环境只有一个身份，按管理员处理。 */
    private static final String FALLBACK_OWNER = "wg";

    private final MemberService memberService;
    private final AccountAuthService accountAuthService;
    private final CurrentMember currentMember;

    public MemberController(MemberService memberService, AccountAuthService accountAuthService,
            CurrentMember currentMember) {
        this.memberService = memberService;
        this.accountAuthService = accountAuthService;
        this.currentMember = currentMember;
    }

    @GetMapping("/members")
    public ApiResponse<List<MemberDto>> members() {
        return ApiResponse.ok(memberService.list());
    }

    @GetMapping("/org")
    public ApiResponse<OrgDto> org() {
        return ApiResponse.ok(memberService.org());
    }

    @PostMapping("/members/accounts")
    public ApiResponse<MemberDto> createAccount(@Valid @RequestBody CreateAccountRequest body,
            HttpServletRequest http) {
        String operator = currentMember.idOr(http, FALLBACK_OWNER);
        return ApiResponse.ok(accountAuthService.adminCreateAccount(operator, body.name(), body.email(), body.password()));
    }

    /** 管理员重置成员密码；密码明文只用于本次请求。 */
    @PatchMapping("/members/{memberId}/password")
    public ApiResponse<Void> resetPassword(@org.springframework.web.bind.annotation.PathVariable String memberId,
            @Valid @RequestBody ResetMemberPasswordRequest body, HttpServletRequest http) {
        accountAuthService.adminResetPassword(currentMember.idOr(http, FALLBACK_OWNER), memberId, body.password());
        return ApiResponse.ok(null);
    }

    /** 管理员删除成员账号及其所有登录凭据。 */
    @DeleteMapping("/members/{memberId}/account")
    public ApiResponse<Void> deleteAccount(@org.springframework.web.bind.annotation.PathVariable String memberId,
            HttpServletRequest http) {
        accountAuthService.adminDeleteAccount(currentMember.idOr(http, FALLBACK_OWNER), memberId);
        return ApiResponse.ok(null);
    }
}
