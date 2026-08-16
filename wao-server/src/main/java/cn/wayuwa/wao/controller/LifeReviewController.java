package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.GenerateLifeReviewRequest;
import cn.wayuwa.wao.dto.LifeReviewDto;
import cn.wayuwa.wao.dto.LifeReviewHistoryDto;
import cn.wayuwa.wao.service.LifeReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 当前成员私有的证据化周期人生回顾接口。 */
@RestController
@RequestMapping("/api/life-reviews")
public class LifeReviewController {
    private final LifeReviewService service;
    private final CurrentMember currentMember;

    public LifeReviewController(LifeReviewService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    /** 读取指定周期的资料覆盖和已有存档。 */
    @GetMapping
    public ApiResponse<LifeReviewDto> overview(
            @RequestParam(defaultValue = "WEEK") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate anchorDate,
            HttpServletRequest request) {
        return ApiResponse.ok(service.overview(currentMember.idOr(request, "unknown"), period, anchorDate));
    }

    /** 调用只读 Codex 生成并按成员、自然周期保存回顾。 */
    @PostMapping("/generate")
    public ApiResponse<LifeReviewDto> generate(@Valid @RequestBody GenerateLifeReviewRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(service.generate(currentMember.idOr(request, "unknown"),
                body.period(), body.anchorDate()));
    }

    /** 读取当前成员最近的回顾存档。 */
    @GetMapping("/history")
    public ApiResponse<List<LifeReviewHistoryDto>> history(HttpServletRequest request) {
        return ApiResponse.ok(service.history(currentMember.idOr(request, "unknown")));
    }
}
