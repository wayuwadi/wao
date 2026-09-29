package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.CreateWorkspaceMcpTokenRequest;
import cn.wayuwa.wao.dto.CreatedWorkspaceMcpTokenDto;
import cn.wayuwa.wao.dto.WorkspaceMcpTokenDto;
import cn.wayuwa.wao.service.WorkspaceMcpTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前成员管理 WAO 工作区 MCP 的独立设备令牌。 */
@RestController
@RequestMapping("/api/workspace-mcp/tokens")
public class WorkspaceMcpTokenController {
    private final WorkspaceMcpTokenService tokens;
    private final CurrentMember currentMember;

    public WorkspaceMcpTokenController(WorkspaceMcpTokenService tokens, CurrentMember currentMember) {
        this.tokens = tokens; this.currentMember = currentMember;
    }

    /** 列出可吊销的令牌元数据。 */
    @GetMapping
    public ResponseEntity<ApiResponse<List<WorkspaceMcpTokenDto>>> list(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(tokens.list(member(request))));
    }

    /** 签发一枚可读或可读写令牌，明文仅返回一次。 */
    @PostMapping
    public ResponseEntity<ApiResponse<CreatedWorkspaceMcpTokenDto>> create(
            @Valid @RequestBody CreateWorkspaceMcpTokenRequest body, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(tokens.create(member(request), body.name(), body.canWrite(),
                        Boolean.TRUE.equals(body.canPublishHostedPage()))));
    }

    /** 吊销当前成员的一枚令牌。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> revoke(@PathVariable String id, HttpServletRequest request) {
        tokens.revoke(member(request), id); return ApiResponse.ok(null);
    }

    private String member(HttpServletRequest request) {
        String id = currentMember.id(request);
        if (!StringUtils.hasText(id)) throw new UnauthorizedException("登录已过期，请重新登录");
        return id;
    }
}
