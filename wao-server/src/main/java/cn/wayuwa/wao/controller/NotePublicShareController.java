package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.service.NotePublicShareService;
import cn.wayuwa.wao.service.ShareService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 小记作者管理限时权限；公开入口仅按不可猜测令牌只读。 */
@RestController
public class NotePublicShareController {
    private final NotePublicShareService service;
    private final CurrentMember member;
    private final ShareService shares;
    private final String baseUrl;
    public record CreateRequest(Integer durationHours) {}
    public NotePublicShareController(NotePublicShareService service,CurrentMember member,ShareService shares,@Value("${wao.share.base-url:}") String baseUrl) {
        this.service=service;this.member=member;this.shares=shares;this.baseUrl=baseUrl;
    }
    @GetMapping("/api/docs/{id}/public-shares")
    public ApiResponse<List<NotePublicShareService.Entry>> list(@PathVariable String id,HttpServletRequest request) { return ApiResponse.ok(service.list(member.id(request),id)); }
    @PostMapping("/api/docs/{id}/public-shares")
    public ApiResponse<NotePublicShareService.Created> create(@PathVariable String id,@RequestBody CreateRequest body,HttpServletRequest request) { return ApiResponse.ok(service.create(member.id(request),id,body.durationHours()==null?168:body.durationHours())); }
    @DeleteMapping("/api/docs/{id}/public-shares/{shareId}")
    public ApiResponse<Void> revoke(@PathVariable String id,@PathVariable String shareId,HttpServletRequest request) { service.revoke(member.id(request),id,shareId);return ApiResponse.ok(null); }
    @GetMapping("/api/share/notes/{token}")
    public ApiResponse<NotePublicShareService.PublicNote> read(@PathVariable String token) { return ApiResponse.ok(service.read(token)); }
    @GetMapping("/api/share/notes/{token}.md")
    public ResponseEntity<byte[]> markdown(@PathVariable String token) {
        String origin=baseUrl.isBlank()?ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString():baseUrl;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(new MediaType("text","markdown",StandardCharsets.UTF_8))
            .body(shares.toMarkdown(service.read(token).doc(),origin).getBytes(StandardCharsets.UTF_8));
    }
    @GetMapping("/api/share/notes/{token}/images/{imageId}/raw")
    public ResponseEntity<byte[]> image(@PathVariable String token,@PathVariable String imageId) {
        var image=service.image(token,imageId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(image.getContentType())).body(image.getContent());
    }
}
