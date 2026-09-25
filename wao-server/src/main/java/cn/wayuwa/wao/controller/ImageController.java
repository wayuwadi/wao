package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.domain.ImageBlobEntity;
import cn.wayuwa.wao.dto.ImageItemDto;
import cn.wayuwa.wao.service.ImageService;
import cn.wayuwa.wao.service.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

/** 图床接口：上传、列表、取图、删除。 */
@RestController
@RequestMapping("/api/images")
public class ImageController {

    private final ImageService images;
    private final CurrentMember currentMember;
    private final ProjectService projects;

    public ImageController(ImageService images, CurrentMember currentMember, ProjectService projects) {
        this.images = images;
        this.currentMember = currentMember;
        this.projects = projects;
    }

    @GetMapping
    public ApiResponse<List<ImageItemDto>> list(@RequestParam(required = false) String projectId,
            HttpServletRequest request) {
        String memberId = currentMember.id(request);
        if (projectId != null) projects.requireAccess(projectId, memberId);
        return ApiResponse.ok(images.list(projects.visibleProjectIds(memberId), projectId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImageItemDto> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(required = false) String projectId, HttpServletRequest request) {
        String memberId = currentMember.id(request);
        if (projectId != null) projects.requireAccess(projectId, memberId);
        return ApiResponse.ok(images.upload(file, source, projectId));
    }

    /** 直出图片正文；内容按 id 寻址且不可变，可长期缓存。 */
    @GetMapping("/{id}/raw")
    public ResponseEntity<byte[]> raw(@PathVariable String id) {
        ImageBlobEntity e = images.raw(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(e.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .body(e.getContent());
    }

    /** 列表用小图，按不可变图片 id 长期缓存；大图仍由 raw 提供。 */
    @GetMapping("/{id}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable String id) {
        ImageService.Preview image = images.preview(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .body(image.content());
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest request) {
        String projectId = images.metadata(id).getProjectId();
        if (projectId != null) projects.requireAccess(projectId, currentMember.id(request));
        images.delete(id);
        return ApiResponse.ok(null);
    }
}
