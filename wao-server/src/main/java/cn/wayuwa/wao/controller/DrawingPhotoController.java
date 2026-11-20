package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.service.DrawingPhotoService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/drawings/photo-jobs")
public class DrawingPhotoController {
    private final DrawingPhotoService photos;
    private final CurrentMember members;

    public DrawingPhotoController(DrawingPhotoService photos, CurrentMember members) {
        this.photos = photos;
        this.members = members;
    }

    @PostMapping
    public ApiResponse<DrawingPhotoService.View> start(@Valid @RequestBody Request input, HttpServletRequest http) {
        return ApiResponse.ok(photos.start(member(http), input.referenceId(), input.requestId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<DrawingPhotoService.View> get(@PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(photos.get(member(http), id));
    }

    private String member(HttpServletRequest http) {
        String id = members.id(http);
        if (id == null) throw new UnauthorizedException("请登录后再识别草图");
        return id;
    }

    public record Request(
            @NotBlank @Pattern(regexp = "[a-f0-9]{32}") String referenceId,
            @NotBlank @Pattern(regexp = "[a-f0-9]{32}") String requestId) {}
}
