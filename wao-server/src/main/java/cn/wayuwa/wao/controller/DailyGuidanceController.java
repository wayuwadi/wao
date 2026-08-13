package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.DailyGuidanceDto;
import cn.wayuwa.wao.service.DailyGuidanceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 首页成员每日情境建议接口。 */
@RestController
@RequestMapping("/api/daily-guidance")
public class DailyGuidanceController {

    private static final String FALLBACK_MEMBER = "wg";
    private final DailyGuidanceService service;
    private final CurrentMember currentMember;

    public DailyGuidanceController(DailyGuidanceService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    /** 读取当前成员今天已缓存的建议及真实可用状态。 */
    @GetMapping
    public ApiResponse<DailyGuidanceDto> today(HttpServletRequest http) {
        return ApiResponse.ok(service.today(member(http)));
    }

    /** 使用本地 Codex 和当前成员的真实未完成任务生成今天建议。 */
    @PostMapping
    public ApiResponse<DailyGuidanceDto> generate(
            @RequestParam(defaultValue = "false") boolean refresh, HttpServletRequest http) {
        return ApiResponse.ok(service.generate(member(http), refresh));
    }

    private String member(HttpServletRequest http) {
        return currentMember.idOr(http, FALLBACK_MEMBER);
    }
}
