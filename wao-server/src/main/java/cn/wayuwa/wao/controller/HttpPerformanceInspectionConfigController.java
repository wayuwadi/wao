package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.HttpPerformanceInspectionConfigDto;
import cn.wayuwa.wao.dto.SaveHttpPerformanceInspectionConfigRequest;
import cn.wayuwa.wao.service.HttpPerformanceInspectionConfigService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内置 HTTP 性能巡检自动任务的专用配置接口。 */
@RestController
@RequestMapping("/api/jobs/job-http-performance-ai-run/config")
public class HttpPerformanceInspectionConfigController {

    private final HttpPerformanceInspectionConfigService settings;

    public HttpPerformanceInspectionConfigController(HttpPerformanceInspectionConfigService settings) {
        this.settings = settings;
    }

    @GetMapping
    public ApiResponse<HttpPerformanceInspectionConfigDto> get() {
        return ApiResponse.ok(settings.get());
    }

    @PutMapping
    public ApiResponse<HttpPerformanceInspectionConfigDto> save(
            @Valid @RequestBody SaveHttpPerformanceInspectionConfigRequest request) {
        return ApiResponse.ok(settings.save(request));
    }
}
