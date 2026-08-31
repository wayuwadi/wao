package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.AdjustFunctionScoreRequest;
import cn.wayuwa.wao.dto.FunctionHealthItemDto;
import cn.wayuwa.wao.dto.FunctionHealthOverviewDto;
import cn.wayuwa.wao.service.FunctionHealthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 工作台功能地图的真实使用度、迭代分与人工校准接口。 */
@RestController
@RequestMapping("/api/function-health")
public class FunctionHealthController {
    private final FunctionHealthService service;
    private final CurrentMember currentMember;
    public FunctionHealthController(FunctionHealthService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }
    @GetMapping
    public ApiResponse<FunctionHealthOverviewDto> overview(HttpServletRequest http) {
        return ApiResponse.ok(service.overview(currentMember.id(http)));
    }
    @PostMapping("/{featureId}/open")
    public ApiResponse<Void> open(@PathVariable String featureId,
            @RequestParam(defaultValue = "PAGE") String source, HttpServletRequest http) {
        service.recordOpen(currentMember.id(http), featureId, source);
        return ApiResponse.ok(null);
    }
    @PostMapping("/{featureId}/score")
    public ApiResponse<FunctionHealthItemDto> adjust(@PathVariable String featureId,
            @Valid @RequestBody AdjustFunctionScoreRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.adjust(currentMember.id(http), featureId, request.delta()));
    }
}
