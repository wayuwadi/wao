package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.service.TextPolishJobService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/text-polish/jobs")
public class TextPolishJobController {
    private final TextPolishJobService service;
    private final CurrentMember member;
    public TextPolishJobController(TextPolishJobService service, CurrentMember member) {
        this.service = service; this.member = member;
    }

    @PostMapping
    public ApiResponse<TextPolishJobService.Result> submit(@Valid @RequestBody Request request, HttpServletRequest http) {
        return ApiResponse.ok(service.submit(member.idOr(http, "unknown"), request.requestId(), request.kind(), request.originalText(),
                request.model(), request.titlePrompt(), request.notePolishPrompt(), request.allowedTags()));
    }

    @GetMapping("/{id}")
    public ApiResponse<TextPolishJobService.Result> get(@PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(service.get(member.idOr(http, "unknown"), id));
    }

    public record Request(@NotBlank @Pattern(regexp = "[a-f0-9]{32}") String requestId,
                          @NotBlank @Pattern(regexp = "DOCUMENT|WORKSPACE|NOTE_TITLE|NOTE_BODY|NOTE_TAGS") String kind,
                          @NotBlank @Size(max = 12000) String originalText,
                          @Size(max = 64) String model, @Size(max = 4000) String titlePrompt,
                          @Size(max = 4000) String notePolishPrompt, @Size(max = 100) List<@NotBlank @Size(max = 32) String> allowedTags) { }
}
