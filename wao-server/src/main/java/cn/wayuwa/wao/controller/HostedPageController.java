package cn.wayuwa.wao.controller;

import java.util.List;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.domain.HostedPageEntity;
import cn.wayuwa.wao.dto.HostedPageDto;
import cn.wayuwa.wao.service.HostedPageService;
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
    public HostedPageController(HostedPageService pages, CurrentMember currentMember) { this.pages = pages; this.currentMember = currentMember; }

    @GetMapping("/hosted-pages")
    public ApiResponse<List<HostedPageDto>> list(
            @RequestParam(defaultValue = "ALL") String sourceType) {
        return ApiResponse.ok(pages.list(sourceType));
    }

    @PostMapping(value = "/hosted-pages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<HostedPageDto> create(@RequestParam MultipartFile html, @RequestParam String slug,
            @RequestParam String title, @RequestParam(required = false) MultipartFile icon, HttpServletRequest request) {
        return ApiResponse.ok(pages.create(html, slug, title, icon, currentMember.id(request)));
    }

    @PutMapping(value = "/hosted-pages/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<HostedPageDto> update(@PathVariable String id, @RequestParam(required = false) MultipartFile html,
            @RequestParam String slug, @RequestParam String title, @RequestParam(required = false) MultipartFile icon) {
        return ApiResponse.ok(pages.update(id, html, slug, title, icon));
    }

    @DeleteMapping("/hosted-pages/{id}") public ApiResponse<Void> delete(@PathVariable String id) { pages.delete(id); return ApiResponse.ok(null); }

    /** 公开直出页面；sandbox 刻意不含 allow-same-origin，隔离 WAO 会话与业务 API。 */
    @GetMapping(value = "/pages/{slug}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> page(@PathVariable String slug) {
        HostedPageEntity page = pages.publicPage(slug);
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
