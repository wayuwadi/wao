package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.GenerateWeeklyReportRequest;
import cn.wayuwa.wao.dto.WeeklyReportDto;
import cn.wayuwa.wao.service.WeeklyReportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 成员私有的结构化周报生成接口。 */
@RestController
@RequestMapping("/api/weekly-report")
public class WeeklyReportController {

    private final WeeklyReportService service;
    private final CurrentMember currentMember;

    public WeeklyReportController(WeeklyReportService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    /** 用当前成员提交的原始记录生成四段周报，不检索或伪造额外事实。 */
    @PostMapping("/generate")
    public ApiResponse<WeeklyReportDto> generate(
            @Valid @RequestBody GenerateWeeklyReportRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.generate(currentMember.idOr(http, "unknown"), request.records()));
    }
}
