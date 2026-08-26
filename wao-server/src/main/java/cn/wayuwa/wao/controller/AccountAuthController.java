package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.AuthSessionManager;
import cn.wayuwa.wao.auth.ExternalIdentityProfile;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.AuthSessionDto;
import cn.wayuwa.wao.dto.ClaimAccountRequest;
import cn.wayuwa.wao.dto.UpdateProfileRequest;
import cn.wayuwa.wao.dto.EmailCredentialRequest;
import cn.wayuwa.wao.dto.EmailLoginRequest;
import cn.wayuwa.wao.dto.EmailRegisterRequest;
import cn.wayuwa.wao.service.AccountAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 邮箱登录、会话恢复、成员认领及当前账号登录方式管理。 */
@RestController
@RequestMapping("/api")
public class AccountAuthController {
    static final String JENKINS_USER_HEADER = "X-WAO-Jenkins-User";
    static final String JENKINS_GROUPS_HEADER = "X-WAO-Jenkins-Groups";
    static final String JENKINS_MEMBER_GROUP = "wao-members";
    static final String JENKINS_ADMIN_GROUP = "wao-admins";

    private final AccountAuthService auth;
    private final AuthSessionManager sessions;

    public AccountAuthController(AccountAuthService auth, AuthSessionManager sessions) {
        this.auth = auth;
        this.sessions = sessions;
    }

    @GetMapping("/auth/session")
    public ApiResponse<AuthSessionDto> session(HttpServletRequest request) {
        return ApiResponse.ok(auth.session(requireAccount(request)));
    }

    @GetMapping("/auth/jenkins")
    public ApiResponse<Void> jenkins(HttpServletRequest request, HttpServletResponse response) {
        AuthSessionDto current = auth.session(requireAccount(request));
        response.setHeader(JENKINS_USER_HEADER, current.member().id());
        String groups = isAdmin(current.member().role())
                ? JENKINS_MEMBER_GROUP + "|" + JENKINS_ADMIN_GROUP
                : JENKINS_MEMBER_GROUP;
        response.setHeader(JENKINS_GROUPS_HEADER, groups);
        return ApiResponse.ok(null);
    }

    @PostMapping("/auth/email/register")
    public ApiResponse<AuthSessionDto> register(@Valid @RequestBody EmailRegisterRequest body,
                                                HttpServletRequest request) {
        AuthSessionDto result = auth.register(body.email(), body.password(), body.name());
        sessions.login(request, result.accountId());
        return ApiResponse.ok(result);
    }

    @PostMapping("/auth/email/login")
    public ApiResponse<AuthSessionDto> login(@Valid @RequestBody EmailLoginRequest body,
                                             HttpServletRequest request) {
        AuthSessionDto result = auth.login(body.email(), body.password());
        sessions.login(request, result.accountId());
        return ApiResponse.ok(result);
    }

    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        sessions.logout(request);
        return ApiResponse.ok(null);
    }

    @PostMapping("/auth/claim")
    public ApiResponse<AuthSessionDto> claim(@Valid @RequestBody ClaimAccountRequest body,
                                             HttpServletRequest request) {
        ExternalIdentityProfile pending = sessions.consumePending(request);
        if (pending == null) throw new UnauthorizedException("认领请求已过期，请重新发起第三方登录");
        AuthSessionDto result = auth.claim(pending, body.memberId());
        sessions.login(request, result.accountId());
        return ApiResponse.ok(result);
    }

    @PutMapping("/account/email")
    public ApiResponse<AuthSessionDto> updateEmail(@Valid @RequestBody EmailCredentialRequest body,
                                                   HttpServletRequest request) {
        return ApiResponse.ok(auth.updateEmailCredential(requireAccount(request), body.email(),
                body.currentPassword(), body.newPassword()));
    }

    @PutMapping("/account/profile")
    public ApiResponse<AuthSessionDto> updateProfile(@Valid @RequestBody UpdateProfileRequest body,
                                                     HttpServletRequest request) {
        return ApiResponse.ok(auth.updateProfile(requireAccount(request), body.name(), body.initial()));
    }

    @DeleteMapping("/account/identities/{provider}")
    public ApiResponse<AuthSessionDto> unlink(@PathVariable String provider, HttpServletRequest request) {
        return ApiResponse.ok(auth.unlink(requireAccount(request), provider));
    }

    private String requireAccount(HttpServletRequest request) {
        String accountId = sessions.currentAccountId(request);
        if (accountId == null) throw new UnauthorizedException("登录已过期，请重新登录");
        return accountId;
    }

    private boolean isAdmin(String role) {
        return "管理员".equals(role) || "超级管理员".equals(role);
    }
}
