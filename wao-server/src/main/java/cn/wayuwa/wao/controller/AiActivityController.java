package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.AiActivityPageDto;
import cn.wayuwa.wao.service.AiActivityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 首页统一 AI 活动的分类与分页入口。 */
@RestController
@RequestMapping("/api/ai/activities")
public class AiActivityController {
    private final AiActivityService service;

    public AiActivityController(AiActivityService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AiActivityPageDto> page(
            @RequestParam(defaultValue = "ALL") String type,
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.page(type, status, page, size));
    }
}
