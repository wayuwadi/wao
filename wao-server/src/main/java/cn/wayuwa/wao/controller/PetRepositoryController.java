package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.service.PetRepositoryService;
import cn.wayuwa.wao.service.WorkspaceMcpTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;

/** 宠物公开浏览与登录作者管理。 */
@RestController
public class PetRepositoryController {
    private final PetRepositoryService pets;
    private final CurrentMember current;
    private final WorkspaceMcpTokenService workspaceTokens;
    public PetRepositoryController(PetRepositoryService pets, CurrentMember current, WorkspaceMcpTokenService workspaceTokens) {
        this.pets = pets; this.current = current; this.workspaceTokens = workspaceTokens;
    }
    /** 上传字节前检查一项明确的 Pet CLI 权限，不接受浏览器会话替代设备授权。 */
    @GetMapping("/api/pets/cli-access")
    public ResponseEntity<ApiResponse<Boolean>> cliAccess(@RequestParam String operation, HttpServletRequest request) {
        if (!List.of("list_my_pets", "upload_pet", "share_pet").contains(operation)) {
            throw new BadRequestException("未知宠物操作");
        }
        tokenMember(request.getHeader(HttpHeaders.AUTHORIZATION), operation);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(true));
    }
    @GetMapping("/api/public/pets")
    public ApiResponse<PetRepositoryService.Page> browse(@RequestParam(defaultValue="") String keyword, @RequestParam(defaultValue="1") int page) { return ApiResponse.ok(pets.browse(keyword, page)); }
    @GetMapping("/api/public/pets/{id}")
    public ApiResponse<PetRepositoryService.Card> detail(@PathVariable String id) { return ApiResponse.ok(pets.detail(id)); }
    @GetMapping("/api/pets/mine")
    public ApiResponse<List<PetRepositoryService.Card>> mine(HttpServletRequest request) { return ApiResponse.ok(pets.mine(operationMember(request, "list_my_pets"))); }
    @PostMapping(value="/api/pets", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PetRepositoryService.Card> create(@RequestPart(required=false) MultipartFile archive,
            @RequestParam(required=false) String manifest, @RequestPart(required=false) MultipartFile sprite,
            @RequestParam String source, @RequestParam String license, HttpServletRequest request) throws IOException {
        String member = operationMember(request, "upload_pet");
        return ApiResponse.ok(pets.create(member, archive == null ? null : archive.getBytes(), manifest,
                sprite == null ? null : sprite.getBytes(), source, license));
    }
    public record Visibility(boolean listed, boolean rightsConfirmed) {}
    @PutMapping("/api/pets/{id}/visibility")
    public ApiResponse<PetRepositoryService.Card> visibility(@PathVariable String id, @RequestBody Visibility body, HttpServletRequest request) {
        return ApiResponse.ok(pets.visibility(operationMember(request, "share_pet"), id, body.listed(), body.rightsConfirmed()));
    }
    @GetMapping({"/api/public/pets/{id}/sprite", "/api/public/pets/{id}/download"})
    public ResponseEntity<byte[]> publicFile(@PathVariable String id, HttpServletRequest request) { return file(id, null, request); }
    @GetMapping({"/api/pets/{id}/sprite", "/api/pets/{id}/download"})
    public ResponseEntity<byte[]> privateFile(@PathVariable String id, HttpServletRequest request) { return file(id, member(request), request); }
    private ResponseEntity<byte[]> file(String id, String member, HttpServletRequest request) {
        boolean sprite = request.getRequestURI().endsWith("/sprite");
        var file = pets.binary(id, member, sprite, request.getRemoteAddr());
        var response = ResponseEntity.ok().contentType(MediaType.parseMediaType(file.type())).contentLength(file.content().length)
                .header("X-Content-Type-Options", "nosniff").header("X-Pet-Sha256", file.sha256()).cacheControl(CacheControl.noStore());
        if (!sprite) response.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName()).build().toString());
        return response.body(file.content());
    }
    private String member(HttpServletRequest request) {
        String member = current.id(request);
        if (member == null) throw new UnauthorizedException("请先登录");
        return member;
    }
    private String operationMember(HttpServletRequest request, String operation) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        return authorization == null ? member(request) : tokenMember(authorization, operation);
    }
    private String tokenMember(String authorization, String operation) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new UnauthorizedException("工作区令牌无效或已吊销");
        }
        var token = workspaceTokens.authenticate(authorization.substring(7).trim());
        if (token == null) throw new UnauthorizedException("工作区令牌无效或已吊销");
        if (!token.permits(operation)) throw new ForbiddenException("此工作区令牌未获宠物操作权限，请在能力中心新建授权并选择对应 Pet 操作");
        return token.memberId();
    }
}
