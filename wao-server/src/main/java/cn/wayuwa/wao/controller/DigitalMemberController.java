package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.DigitalMemberDtos.*;
import cn.wayuwa.wao.service.DigitalMemberService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 数字成员图库只使用服务端会话的拥有者，不接受客户端 owner/member 标识。 */
@RestController
public class DigitalMemberController {
    private final DigitalMemberService service;
    private final CurrentMember current;
    public DigitalMemberController(DigitalMemberService service,CurrentMember current){this.service=service;this.current=current;}
    @GetMapping("/api/digital-members")
    public ApiResponse<Library> library(HttpServletRequest r){return ApiResponse.ok(service.library(current.id(r)));}
    @PostMapping("/api/digital-members")
    public ApiResponse<Subject> create(@RequestBody Create body,HttpServletRequest r){return ApiResponse.ok(service.create(current.id(r),body));}
    @GetMapping("/api/digital-members/{id}")
    public ApiResponse<Subject> subject(@PathVariable String id,HttpServletRequest r){return ApiResponse.ok(service.subject(current.id(r),id));}
    @PatchMapping("/api/digital-members/{id}")
    public ApiResponse<Subject> update(@PathVariable String id,@RequestBody Update body,HttpServletRequest r){return ApiResponse.ok(service.update(current.id(r),id,body));}
    @PostMapping(value="/api/digital-members/{id}/assets",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Asset> upload(@PathVariable String id,@RequestParam MultipartFile file,@RequestParam(defaultValue="reference") String role,HttpServletRequest r){return ApiResponse.ok(service.upload(current.id(r),id,file,role));}
    @GetMapping("/api/digital-assets/{id}/raw")
    public ResponseEntity<byte[]> raw(@PathVariable String id,@RequestParam(defaultValue="false") boolean preview,HttpServletRequest r){var content=service.raw(current.id(r),id);if(preview)content=cn.wayuwa.wao.service.DigitalImagePreview.create(content);return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType())).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(content.bytes());}
    @DeleteMapping("/api/digital-assets/{id}")
    public ApiResponse<Void> delete(@PathVariable String id,HttpServletRequest r){service.deleteAsset(current.id(r),id);return ApiResponse.ok(null);}
    @PostMapping("/api/digital-members/{id}/looks")
    public ApiResponse<Look> createLook(@PathVariable String id,@RequestBody CreateLook body,HttpServletRequest r){return ApiResponse.ok(service.createLook(current.id(r),id,body));}
    @PostMapping("/api/digital-looks/{id}/versions")
    public ApiResponse<Look> createVersion(@PathVariable String id,@RequestBody CreateVersion body,HttpServletRequest r){return ApiResponse.ok(service.createVersion(current.id(r),id,body));}
    @PostMapping("/api/digital-versions/{id}/confirm")
    public ApiResponse<Subject> confirm(@PathVariable String id,HttpServletRequest r){return ApiResponse.ok(service.confirm(current.id(r),id));}
    @GetMapping("/api/me/profile")
    public ApiResponse<Profile> profile(HttpServletRequest r){return ApiResponse.ok(service.profile(current.id(r)));}
    @PutMapping("/api/me/profile")
    public ApiResponse<Profile> profile(@RequestBody ProfileUpdate body,HttpServletRequest r){return ApiResponse.ok(service.updateProfile(current.id(r),body));}
    @GetMapping("/api/me/digital-member")
    public ApiResponse<Binding> binding(HttpServletRequest r){return ApiResponse.ok(service.binding(current.id(r)));}
    @PutMapping("/api/me/digital-member")
    public ApiResponse<Binding> binding(@RequestBody Bind body,HttpServletRequest r){return ApiResponse.ok(service.bind(current.id(r),body));}
    @GetMapping("/api/assistant/image-capabilities")
    public ApiResponse<Capability> capability(HttpServletRequest r){service.profile(current.id(r));return ApiResponse.ok(service.capability());}
    @PostMapping("/api/digital-members/{id}/generate")
    public ApiResponse<Generation> generate(@PathVariable String id,@RequestBody Generate body,HttpServletRequest r){return ApiResponse.ok(service.generate(current.id(r),id,body));}
    @GetMapping("/api/digital-members/{id}/generations/{run}")
    public ApiResponse<Generation> generation(@PathVariable String id,@PathVariable String run,HttpServletRequest r){return ApiResponse.ok(service.generation(current.id(r),id,run));}
}
