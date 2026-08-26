package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.SystemPeriodicReportDto;
import cn.wayuwa.wao.dto.SaveSystemReportSettingsRequest;
import cn.wayuwa.wao.dto.SystemReportSettingsDto;
import cn.wayuwa.wao.service.SystemPeriodicReportService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 团队共享的系统日报、周报和月报接口。 */
@RestController
@RequestMapping("/api/system-reports")
public class SystemPeriodicReportController {
    private final SystemPeriodicReportService service;

    public SystemPeriodicReportController(SystemPeriodicReportService service) {
        this.service = service;
    }

    /** 读取指定完整自然周期；尚未产出时返回范围和空报告状态。 */
    @GetMapping
    public ApiResponse<SystemPeriodicReportDto> overview(
            @RequestParam(defaultValue = "DAY") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate anchorDate) {
        return ApiResponse.ok(service.overview(period, anchorDate));
    }

    /** 用当前数据库真实数据幂等补齐指定完整周期。 */
    @PostMapping("/generate")
    public ApiResponse<SystemPeriodicReportDto> generate(
            @RequestParam(defaultValue = "DAY") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate anchorDate) {
        return ApiResponse.ok(service.generate(period, anchorDate));
    }

    /** 读取团队共享的自动刷新时间、频率、报告类型和汇总项。 */
    @GetMapping("/settings")
    public ApiResponse<SystemReportSettingsDto> settings() {
        return ApiResponse.ok(service.settings());
    }

    /** 校验并保存团队共享系统报告设置。 */
    @PutMapping("/settings")
    public ApiResponse<SystemReportSettingsDto> saveSettings(
            @RequestBody SaveSystemReportSettingsRequest request) {
        return ApiResponse.ok(service.saveSettings(request));
    }
}
