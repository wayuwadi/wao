package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.AppearancePhotoEntity;
import cn.wayuwa.wao.dto.AppearancePhotoDto;
import cn.wayuwa.wao.dto.AppearanceWorkspaceDto;
import cn.wayuwa.wao.service.AppearanceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 当前登录成员的形象管理工作区和私有实拍接口。 */
@RestController
@RequestMapping("/api/appearance")
public class AppearanceController {
    private final AppearanceService service;
    private final CurrentMember currentMember;

    public AppearanceController(AppearanceService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    /** 读取当前成员工作区。 */
    @GetMapping
    public ApiResponse<AppearanceWorkspaceDto> workspace(HttpServletRequest request) {
        return ApiResponse.ok(service.workspace(member(request)));
    }

    /** 保存当前成员工作区。 */
    @PutMapping
    public ApiResponse<AppearanceWorkspaceDto> save(@RequestBody AppearanceWorkspaceDto body,
            HttpServletRequest request) {
        return ApiResponse.ok(service.save(member(request), body));
    }

    /** 上传当前成员私有实拍。 */
    @PostMapping(value = "/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AppearancePhotoDto> upload(@RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        return ApiResponse.ok(service.uploadPhoto(member(request), file));
    }

    /** 读取当前成员实拍；禁止公共缓存。 */
    @GetMapping("/photos/{id}")
    public ResponseEntity<byte[]> photo(@PathVariable String id, HttpServletRequest request) {
        AppearancePhotoEntity photo = service.photo(member(request), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.getContentType()))
                .header("Cache-Control", "private, no-store")
                .header("Content-Length", Long.toString(photo.getByteSize()))
                .body(photo.getFileContent());
    }

    /** 删除当前成员明确移除的实拍。 */
    @DeleteMapping("/photos/{id}")
    public ApiResponse<Void> deletePhoto(@PathVariable String id, HttpServletRequest request) {
        service.deletePhoto(member(request), id);
        return ApiResponse.ok(null);
    }

    private String member(HttpServletRequest request) {
        String memberId = currentMember.id(request);
        if (!StringUtils.hasText(memberId)) throw new UnauthorizedException("登录成员不存在，请重新登录");
        return memberId;
    }
}
