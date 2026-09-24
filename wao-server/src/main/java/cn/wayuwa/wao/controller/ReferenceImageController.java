package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.ReferenceImageDto;
import cn.wayuwa.wao.service.ReferenceImageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 个人参考图库：免表单收图、列表和原图读取。 */
@RestController
@RequestMapping("/api/reference-images")
public class ReferenceImageController {
    private final ReferenceImageService images;
    private final CurrentMember currentMember;

    public ReferenceImageController(ReferenceImageService images, CurrentMember currentMember) {
        this.images = images;
        this.currentMember = currentMember;
    }

    @GetMapping
    public ApiResponse<List<ReferenceImageDto>> list(HttpServletRequest request) {
        return ApiResponse.ok(images.list(currentMember.id(request)));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ReferenceImageDto> upload(@RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        return ApiResponse.ok(images.upload(currentMember.id(request), file));
    }

    @GetMapping("/{id}/raw")
    public ResponseEntity<byte[]> raw(@PathVariable String id, HttpServletRequest request) {
        var content = images.raw(currentMember.id(request), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .body(content.bytes());
    }
}
