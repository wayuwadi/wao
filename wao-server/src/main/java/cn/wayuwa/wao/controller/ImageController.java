package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.domain.ImageBlobEntity;
import cn.wayuwa.wao.dto.ImageItemDto;
import cn.wayuwa.wao.service.ImageService;
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

    public ImageController(ImageService images) {
        this.images = images;
    }

    @GetMapping
    public ApiResponse<List<ImageItemDto>> list() {
        return ApiResponse.ok(images.list());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImageItemDto> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "source", required = false) String source) {
        return ApiResponse.ok(images.upload(file, source));
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

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        images.delete(id);
        return ApiResponse.ok(null);
    }
}
