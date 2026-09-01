package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.AccountResourceDto;
import cn.wayuwa.wao.dto.AccountResourceIntegrationDto;
import cn.wayuwa.wao.dto.AccountResourceSecretDto;
import cn.wayuwa.wao.dto.RotatedIntegrationTokenDto;
import cn.wayuwa.wao.dto.SaveAccountResourceIntegrationRequest;
import cn.wayuwa.wao.dto.SaveAccountResourceRequest;
import cn.wayuwa.wao.dto.VerificationMessageDto;
import cn.wayuwa.wao.service.AccountResourceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 已登录成员管理组织共享账号、短信历史和机器集成。 */
@RestController
@RequestMapping("/api/account-resources")
public class AccountResourceController {
    private static final String FALLBACK_MEMBER = "wg";
    private final AccountResourceService service;
    private final CurrentMember currentMember;

    public AccountResourceController(AccountResourceService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    @GetMapping
    public ApiResponse<List<AccountResourceDto>> list() {
        return ApiResponse.ok(service.listAccounts());
    }

    @PostMapping
    public ApiResponse<AccountResourceDto> create(@Valid @RequestBody SaveAccountResourceRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(service.createAccount(request, currentMember.idOr(http, FALLBACK_MEMBER)));
    }

    @PutMapping("/{id}")
    public ApiResponse<AccountResourceDto> update(@PathVariable String id,
            @Valid @RequestBody SaveAccountResourceRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.updateAccount(id, request, currentMember.idOr(http, FALLBACK_MEMBER)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, @RequestParam int revision) {
        service.deleteAccount(id, revision);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/reveal")
    public ApiResponse<AccountResourceSecretDto> reveal(@PathVariable String id) {
        return ApiResponse.ok(new AccountResourceSecretDto(service.revealSecret(id)));
    }

    @GetMapping("/messages")
    public ApiResponse<List<VerificationMessageDto>> messages() {
        return ApiResponse.ok(service.recentMessages());
    }

    @PostMapping("/messages/{id}/forward")
    public ApiResponse<VerificationMessageDto> retryForward(@PathVariable String id) {
        return ApiResponse.ok(service.retryForward(id));
    }

    @GetMapping("/integration")
    public ApiResponse<AccountResourceIntegrationDto> integration() {
        return ApiResponse.ok(service.integration());
    }

    @PutMapping("/integration")
    public ApiResponse<AccountResourceIntegrationDto> saveIntegration(
            @Valid @RequestBody SaveAccountResourceIntegrationRequest request) {
        return ApiResponse.ok(service.saveIntegration(request));
    }

    @PostMapping("/integration/tokens/{type}/rotate")
    public ApiResponse<RotatedIntegrationTokenDto> rotate(@PathVariable String type) {
        return ApiResponse.ok(service.rotateToken(type));
    }
}
