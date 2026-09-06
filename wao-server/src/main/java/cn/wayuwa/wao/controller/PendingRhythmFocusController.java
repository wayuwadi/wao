package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.CompletePendingRhythmFocusRequest;
import cn.wayuwa.wao.dto.PendingRhythmFocusDto;
import cn.wayuwa.wao.service.PendingRhythmFocusService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/** 历史重点的读取与完成均严格绑定当前会话成员。 */
@RestController
@RequestMapping("/api/workday-rhythm/focus/pending")
public class PendingRhythmFocusController {
    private final CurrentMember currentMember;
    private final PendingRhythmFocusService service;

    public PendingRhythmFocusController(CurrentMember currentMember, PendingRhythmFocusService service) {
        this.currentMember = currentMember;
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PendingRhythmFocusDto> pending(HttpServletRequest request) {
        return ApiResponse.ok(service.pending(member(request), today()));
    }

    @PutMapping("/{date}/{index}/completion")
    public ApiResponse<Void> complete(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @PathVariable int index, @Valid @RequestBody CompletePendingRhythmFocusRequest body,
            HttpServletRequest request) {
        service.complete(member(request), date, today(), index, body.expectedTitle(), body.completed());
        return ApiResponse.ok(null);
    }

    private LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }

    private String member(HttpServletRequest request) {
        String id = currentMember.id(request);
        if (id == null || id.isBlank()) throw new UnauthorizedException("请先登录");
        return id;
    }
}
