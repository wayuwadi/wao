package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.AiRunTrendItem;
import cn.wayuwa.wao.dto.DashboardSummaryDto;
import cn.wayuwa.wao.dto.EngineUsageItem;
import cn.wayuwa.wao.dto.TaskTrendItem;
import cn.wayuwa.wao.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 仪表盘统计接口：AI 运行频次与 Token 用量、任务完成趋势、引擎占比、顶部指标卡片。 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ApiResponse<DashboardSummaryDto> summary() {
        return ApiResponse.ok(service.summary());
    }

    @GetMapping("/ai-runs")
    public ApiResponse<List<AiRunTrendItem>> aiRuns(@RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(service.aiRunTrend(days));
    }

    @GetMapping("/tasks")
    public ApiResponse<List<TaskTrendItem>> tasks(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(service.taskTrend(days));
    }

    @GetMapping("/engines")
    public ApiResponse<List<EngineUsageItem>> engines() {
        return ApiResponse.ok(service.engineUsage());
    }
}
