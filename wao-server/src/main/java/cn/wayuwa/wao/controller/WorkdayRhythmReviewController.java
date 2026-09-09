package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.WorkdayRhythmReviewDto;
import cn.wayuwa.wao.service.WorkdayRhythmReviewService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/** 复盘读写仅接受当前登录成员身份。 */
@RestController
@RequestMapping("/api/workday-rhythm/days/{date}/review")
public class WorkdayRhythmReviewController {
    private final CurrentMember currentMember;
    private final WorkdayRhythmReviewService service;

    public WorkdayRhythmReviewController(CurrentMember currentMember, WorkdayRhythmReviewService service) {
        this.currentMember = currentMember;
        this.service = service;
    }

    @GetMapping
    public ApiResponse<WorkdayRhythmReviewDto> get(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, HttpServletRequest request) {
        return ApiResponse.ok(service.get(member(request), date));
    }

    @PutMapping
    public ApiResponse<WorkdayRhythmReviewDto> save(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody WorkdayRhythmReviewDto body, HttpServletRequest request) {
        return ApiResponse.ok(service.save(member(request), date, body));
    }

    private String member(HttpServletRequest request) {
        String id = currentMember.id(request);
        if (id == null || id.isBlank()) throw new UnauthorizedException("请先登录");
        return id;
    }
}
