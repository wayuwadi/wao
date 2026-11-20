package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.service.DrawingAiJobService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/drawings")
public class DrawingAiJobController {
    private final DrawingAiJobService jobs;
    private final CurrentMember members;

    public DrawingAiJobController(DrawingAiJobService jobs, CurrentMember members) {
        this.jobs = jobs;
        this.members = members;
    }

    @PostMapping("/photo-jobs")
    public ApiResponse<DrawingAiJobService.View> start(@Valid @RequestBody Request input, HttpServletRequest http) {
        return ApiResponse.ok(jobs.startPhoto(member(http), input.referenceId(), input.requestId()));
    }

    @GetMapping("/photo-jobs/{id}")
    public ApiResponse<DrawingAiJobService.View> get(@PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(jobs.getPhoto(member(http), id));
    }

    @PostMapping("/layout-jobs")
    public ApiResponse<DrawingAiJobService.View> startLayout(
            @Valid @RequestBody LayoutRequest input, HttpServletRequest http) {
        return ApiResponse.ok(jobs.startLayout(member(http), input.graph(), input.requestId()));
    }

    @GetMapping("/layout-jobs/{id}")
    public ApiResponse<DrawingAiJobService.View> getLayout(@PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(jobs.getLayout(member(http), id));
    }

    private String member(HttpServletRequest http) {
        String id = members.id(http);
        if (id == null) throw new UnauthorizedException("请登录后使用草图 AI");
        return id;
    }

    public record LayoutRequest(
            @NotBlank @Size(max = 65_000) String graph,
            @NotBlank @Pattern(regexp = "[a-f0-9]{32}") String requestId) {}

    public record Request(
            @NotBlank @Pattern(regexp = "[a-f0-9]{32}") String referenceId,
            @NotBlank @Pattern(regexp = "[a-f0-9]{32}") String requestId) {}
}
