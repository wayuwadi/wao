package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.dto.FeishuDailyArchiveDto;
import cn.wayuwa.wao.service.FeishuDailyArchiveService;
import org.springframework.web.bind.annotation.*;

/** 管理员浏览器会话的每日归档开关与状态，不提供手动抓取或补录入口。 */
@RestController
@RequestMapping("/api/feishu/daily-archive")
public class FeishuDailyArchiveController {
    private final FeishuDailyArchiveService service;
    public FeishuDailyArchiveController(FeishuDailyArchiveService service) { this.service = service; }
    @GetMapping
    public ApiResponse<FeishuDailyArchiveDto> state() { return ApiResponse.ok(service.state()); }
    @PutMapping
    public ApiResponse<FeishuDailyArchiveDto> configure(@RequestBody Config request) {
        if (request.enabled() == null || request.expectedEnabled() == null)
            throw new BadRequestException("请指定每日归档开关及读取时的状态");
        return ApiResponse.ok(service.configure(request.enabled(), request.expectedEnabled()));
    }
    public record Config(Boolean enabled, Boolean expectedEnabled) { }
}
