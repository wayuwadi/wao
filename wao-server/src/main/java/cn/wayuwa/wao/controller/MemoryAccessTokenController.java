package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.CreateMemoryAccessTokenRequest;
import cn.wayuwa.wao.dto.CreatedMemoryAccessTokenDto;
import cn.wayuwa.wao.dto.MemoryAccessTokenDto;
import cn.wayuwa.wao.dto.MemoryGrantDto;
import cn.wayuwa.wao.dto.SaveMemoryGrantsRequest;
import cn.wayuwa.wao.service.MemoryAccessTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前成员管理 Codex 共享记忆 MCP 的按设备令牌。 */
@RestController
@RequestMapping("/api/memory/tokens")
public class MemoryAccessTokenController {

    private final MemoryAccessTokenService service;
    private final CurrentMember currentMember;

    public MemoryAccessTokenController(MemoryAccessTokenService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    /** 列出当前成员的有效令牌。 */
    @GetMapping
    public ApiResponse<List<MemoryAccessTokenDto>> list(HttpServletRequest request) {
        return ApiResponse.ok(service.list(member(request)));
    }

    /** 签发一枚只显示一次明文的令牌。 */
    @PostMapping
    public ApiResponse<CreatedMemoryAccessTokenDto> create(
            @Valid @RequestBody CreateMemoryAccessTokenRequest body, HttpServletRequest request) {
        return ApiResponse.ok(service.create(member(request), body.name()));
    }

    /** 吊销当前成员指定的设备令牌。 */
    @DeleteMapping("/{tokenId}")
    public ApiResponse<Void> revoke(@PathVariable String tokenId, HttpServletRequest request) {
        service.revoke(member(request), tokenId);
        return ApiResponse.ok(null);
    }

    /** 列出设备可访问的个人、组织和项目上下文。 */
    @GetMapping("/{tokenId}/grants")
    public ApiResponse<List<MemoryGrantDto>> grants(@PathVariable String tokenId, HttpServletRequest request) {
        return ApiResponse.ok(service.listGrants(member(request), tokenId));
    }

    /** 替换设备的组织/项目授权；个人域授权由服务端固定保留。 */
    @PutMapping("/{tokenId}/grants")
    public ApiResponse<List<MemoryGrantDto>> saveGrants(@PathVariable String tokenId,
            @Valid @RequestBody SaveMemoryGrantsRequest body, HttpServletRequest request) {
        return ApiResponse.ok(service.saveGrants(member(request), tokenId, body.grants()));
    }

    private String member(HttpServletRequest request) {
        String memberId = currentMember.id(request);
        if (!StringUtils.hasText(memberId)) throw new UnauthorizedException("登录成员不存在，请重新登录");
        return memberId;
    }
}
