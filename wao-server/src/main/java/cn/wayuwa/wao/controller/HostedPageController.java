package cn.wayuwa.wao.controller;

import java.util.List;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.ForbiddenException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.HostedPageEntity;
import cn.wayuwa.wao.dto.HostedPageDto;
import cn.wayuwa.wao.service.HostedPageService;
import cn.wayuwa.wao.service.ProjectService;
import cn.wayuwa.wao.service.WorkspaceMcpTokenService;
import cn.wayuwa.wao.service.WorkspaceMcpTokenService.AuthenticatedToken;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** HTML 托管页的站内管理接口与公开页面出口。 */
@RestController
@RequestMapping("/api")
public class HostedPageController {
    private final HostedPageService pages;
    private final CurrentMember currentMember;
    private final ProjectService projects;
    private final WorkspaceMcpTokenService workspaceTokens;
    public HostedPageController(HostedPageService pages, CurrentMember currentMember, ProjectService projects,
            WorkspaceMcpTokenService workspaceTokens) {
        this.pages = pages; this.currentMember = currentMember; this.projects = projects;
        this.workspaceTokens = workspaceTokens;
    }

    @GetMapping("/hosted-pages")
    public ApiResponse<List<HostedPageDto>> list(
            @RequestParam(defaultValue = "ALL") String sourceType,
            @RequestParam(required = false) String projectId, HttpServletRequest request) {
        String memberId = currentMember.id(request);
        if (projectId != null) projects.requireAccess(projectId, memberId);
        return ApiResponse.ok(pages.list(sourceType, projects.visibleProjectIds(memberId), projectId));
    }

    @PostMapping(value = "/hosted-pages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<HostedPageDto> create(@RequestParam MultipartFile html, @RequestParam String slug,
            @RequestParam String title, @RequestParam(required = false) MultipartFile icon,
            @RequestParam(required = false) String projectId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request) {
        String memberId = createMember(authorization, request);
        if (projectId != null) projects.requireAccess(projectId, memberId);
        return ApiResponse.ok(pages.create(html, slug, title, icon, memberId, projectId));
    }

    @GetMapping("/hosted-pages/publish-access")
    public ApiResponse<Boolean> publishAccess(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) String projectId) {
        AuthenticatedToken token = requireHostedPagePublisher(authorization);
        if (projectId != null) projects.requireAccess(projectId, token.memberId());
        return ApiResponse.ok(true);
    }

    private String createMember(String authorization, HttpServletRequest request) {
        if (authorization != null) return requireHostedPagePublisher(authorization).memberId();
        String memberId = currentMember.id(request);
        if (memberId == null || memberId.isBlank()) throw new UnauthorizedException("登录已过期，请重新登录");
        return memberId;
    }

    private AuthenticatedToken requireHostedPagePublisher(String authorization) {
        if (authorization == null || authorization.length() < 8
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new UnauthorizedException("工作区令牌无效或已吊销");
        }
        AuthenticatedToken token = workspaceTokens.authenticate(authorization.substring(7).trim());
        if (token == null) throw new UnauthorizedException("工作区令牌无效或已吊销");
        if (!token.canPublishHostedPage()) {
            throw new ForbiddenException("此工作区令牌未获 HTML 页面发布权限，请重新生成并开启该权限");
        }
        return token;
    }

    @PutMapping(value = "/hosted-pages/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<HostedPageDto> update(@PathVariable String id, @RequestParam(required = false) MultipartFile html,
            @RequestParam String slug, @RequestParam String title, @RequestParam(required = false) MultipartFile icon,
            HttpServletRequest request) {
        String projectId = pages.projectId(id);
        if (projectId != null) projects.requireAccess(projectId, currentMember.id(request));
        return ApiResponse.ok(pages.update(id, html, slug, title, icon));
    }

    @PutMapping(value = "/hosted-pages/{id}/content", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<HostedPageDto> replace(@PathVariable String id, @RequestParam MultipartFile html,
            HttpServletRequest request) {
        String memberId = currentMember.id(request);
        String projectId = pages.projectId(id);
        if (projectId != null) projects.requireAccess(projectId, memberId);
        return ApiResponse.ok(pages.replace(id, html));
    }

    @DeleteMapping("/hosted-pages/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest request) {
        String projectId = pages.projectId(id);
        if (projectId != null) projects.requireAccess(projectId, currentMember.id(request));
        pages.delete(id);
        return ApiResponse.ok(null);
    }

    /** 公开直出页面；sandbox 刻意不含 allow-same-origin，隔离 WAO 会话与业务 API。 */
    @GetMapping(value = "/pages/{slug}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> page(@PathVariable String slug) {
        HostedPageEntity page = pages.publicPage(slug);
        if (pages.isManualPage(page)) {
            return ResponseEntity.ok()
                    .contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                    .cacheControl(CacheControl.noStore())
                    .header("Content-Security-Policy", "frame-ancestors 'self'")
                    .header("X-Content-Type-Options", "nosniff")
                    .body(pages.renderInteractive(page));
        }
        return ResponseEntity.ok().contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", "sandbox allow-scripts allow-modals allow-popups; default-src * data: blob: 'unsafe-inline' 'unsafe-eval';")
                .header("X-Content-Type-Options", "nosniff").body(pages.render(page));
    }

    /** 公开 favicon。 */
    @GetMapping("/pages/{slug}/icon")
    public ResponseEntity<byte[]> icon(@PathVariable String slug) {
        HostedPageEntity page = pages.publicPage(slug);
        if (page.getIconContent() == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(page.getIconContentType()))
                .cacheControl(CacheControl.noCache()).header("X-Content-Type-Options", "nosniff").body(page.getIconContent());
    }
}
