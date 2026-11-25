package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.ForbiddenException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.dto.AgyQuotaAccountDto;
import cn.wayuwa.wao.dto.AgyQuotaGroupDto;
import cn.wayuwa.wao.dto.AgyQuotaReport;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.service.AgyQuotaService;
import cn.wayuwa.wao.service.WorkspaceMcpTokenService;
import cn.wayuwa.wao.service.WorkspaceMcpTokenService.AuthenticatedToken;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 已登录成员读取 Agy 额度汇总；管理员或显式授权的 CLI 令牌可上报脱敏额度快照。 */
@RestController
@RequestMapping("/api/agy-quota")
public class AgyQuotaController {
    private final AgyQuotaService service;
    private final CurrentMember current;
    private final MemberMapper members;
    private final WorkspaceMcpTokenService workspaceTokens;

    public AgyQuotaController(
            AgyQuotaService service,
            CurrentMember current,
            MemberMapper members,
            WorkspaceMcpTokenService workspaceTokens) {
        this.service = service;
        this.current = current;
        this.members = members;
        this.workspaceTokens = workspaceTokens;
    }

    public record ReportRequest(String source, String accountLabel, List<AgyQuotaGroupDto> groups) {
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<AgyQuotaAccountDto>>> list(HttpServletRequest http) {
        if (current.id(http) == null) throw new UnauthorizedException("请先登录");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(service.listAccounts()));
    }

    @PostMapping("/report")
    public ResponseEntity<ApiResponse<AgyQuotaAccountDto>> report(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) ReportRequest request,
            HttpServletRequest http) {
        authorizeReporter(authorization, http);
        if (request == null) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(ApiResponse.ok(service.report(null, null, Instant.now())));
        }
        AgyQuotaAccountDto saved = service.report(
                request.source(),
                new AgyQuotaReport(request.accountLabel(), request.groups()),
                Instant.now());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(saved));
    }

    private void authorizeReporter(String authorization, HttpServletRequest http) {
        if (StringUtils.hasText(authorization)) {
            if (!authorization.startsWith("Bearer ")) {
                throw new UnauthorizedException("工作区令牌格式无效");
            }
            AuthenticatedToken token = workspaceTokens.authenticate(authorization.substring("Bearer ".length()).trim());
            if (token == null) {
                throw new UnauthorizedException("工作区令牌无效或已吊销");
            }
            if (!token.permits("report_agy_quota")) {
                throw new ForbiddenException("工作区令牌未授权上报 Agy 额度");
            }
            return;
        }
        String memberId = current.id(http);
        if (!StringUtils.hasText(memberId)) {
            throw new UnauthorizedException("请先登录");
        }
        MemberEntity member = members.selectById(memberId);
        if (member == null || !Set.of("管理员", "超级管理员").contains(member.getRole())) {
            throw new ForbiddenException("只有管理员或授权的 CLI 令牌可以上报 Agy 额度");
        }
    }
}
