package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.NoteMiningDto;
import cn.wayuwa.wao.service.NoteMiningService;
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

/** 当前成员的小记 AI 挖掘入口。 */
@RestController
@RequestMapping("/api/note-mining")
public class NoteMiningController {
    private final NoteMiningService service;
    private final CurrentMember member;

    public NoteMiningController(NoteMiningService service, CurrentMember member) {
        this.service = service;
        this.member = member;
    }

    @GetMapping
    public ApiResponse<NoteMiningDto> latest(HttpServletRequest request) {
        return ApiResponse.ok(service.latest(member.idOr(request, "unknown")));
    }

    @PostMapping("/runs")
    public ApiResponse<NoteMiningDto> start(@Valid @RequestBody StartRequest body, HttpServletRequest request) {
        return ApiResponse.ok(service.start(member.idOr(request, "unknown"), body.requestId()));
    }

    @GetMapping("/runs/{id}")
    public ApiResponse<NoteMiningDto> get(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(service.get(member.idOr(request, "unknown"), id));
    }

    @PostMapping("/artifacts/{id}/feedback")
    public ApiResponse<NoteMiningDto.Artifact> feedback(@PathVariable String id,
            @Valid @RequestBody FeedbackRequest body, HttpServletRequest request) {
        return ApiResponse.ok(service.feedback(member.idOr(request, "unknown"), id, body.status()));
    }

    @PostMapping("/runs/{id}/visuals/{lens}/retry")
    public ApiResponse<NoteMiningDto.Visual> retryVisual(@PathVariable String id, @PathVariable String lens,
            HttpServletRequest request) {
        return ApiResponse.ok(service.retryVisual(member.idOr(request, "unknown"), id, lens));
    }

    @PostMapping("/runs/{id}/visuals/{lens}/feedback")
    public ApiResponse<NoteMiningDto.Visual> feedbackVisual(@PathVariable String id, @PathVariable String lens,
            @Valid @RequestBody VisualFeedbackRequest body, HttpServletRequest request) {
        return ApiResponse.ok(service.feedbackVisual(member.idOr(request, "unknown"), id, lens, body.status()));
    }

    public record StartRequest(@NotBlank @Pattern(regexp = "[a-f0-9]{32}") String requestId) { }
    public record FeedbackRequest(@NotBlank @Pattern(regexp = "SAVED|NOT_ACCURATE|REMINDER_DRAFT") String status) { }
    public record VisualFeedbackRequest(@NotBlank @Pattern(regexp = "SAVED|NOT_ACCURATE") String status) { }
}
