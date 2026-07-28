package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.AiAccountDto;
import cn.wayuwa.wao.dto.AiAccountFormRequest;
import cn.wayuwa.wao.dto.AiAccountLoginStartDto;
import cn.wayuwa.wao.dto.AiAccountSyncResultDto;
import cn.wayuwa.wao.dto.CompleteAiAccountLoginRequest;
import cn.wayuwa.wao.dto.StartAiAccountLoginRequest;
import cn.wayuwa.wao.service.AiAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AI 账号集中管理：Claude Code / Codex / Qoder 的凭据与额度快照。 */
@RestController
@RequestMapping("/api/settings/ai-accounts")
public class AiAccountController {

    private final AiAccountService service;
    private final CurrentMember currentMember;

    public AiAccountController(AiAccountService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    @GetMapping
    public ApiResponse<List<AiAccountDto>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    public ApiResponse<AiAccountDto> create(@Valid @RequestBody AiAccountFormRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AiAccountDto> update(@PathVariable String id,
            @Valid @RequestBody AiAccountFormRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/sync")
    public ApiResponse<AiAccountSyncResultDto> sync(@PathVariable String id) {
        return ApiResponse.ok(service.sync(id));
    }

    /** 发起 OAuth 登录，返回让用户在浏览器里打开的授权地址。 */
    @PostMapping("/login/start")
    public ApiResponse<AiAccountLoginStartDto> startLogin(@Valid @RequestBody StartAiAccountLoginRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(service.startLogin(request, currentMember.idOr(http, "unknown")));
    }

    /** 回填授权码完成登录，直接返回建好的账号。 */
    @PostMapping("/login/complete")
    public ApiResponse<AiAccountDto> completeLogin(@Valid @RequestBody CompleteAiAccountLoginRequest request) {
        return ApiResponse.ok(service.completeLogin(request));
    }
}
