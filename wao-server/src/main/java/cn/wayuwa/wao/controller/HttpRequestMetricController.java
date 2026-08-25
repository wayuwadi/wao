package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.RequestMetricAcceptedDto;
import cn.wayuwa.wao.dto.RequestMetricBatchRequest;
import cn.wayuwa.wao.dto.RequestMetricClearedDto;
import cn.wayuwa.wao.dto.RequestMetricOverviewDto;
import cn.wayuwa.wao.service.HttpRequestMetricService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 当前成员浏览器 HTTP 请求体验指标接口。 */
@RestController
@RequestMapping("/api/request-metrics")
public class HttpRequestMetricController {

    private final HttpRequestMetricService metrics;
    private final CurrentMember currentMember;

    public HttpRequestMetricController(HttpRequestMetricService metrics, CurrentMember currentMember) {
        this.metrics = metrics;
        this.currentMember = currentMember;
    }

    /** 接收浏览器的有界批量指标，成员身份只从服务端会话解析。 */
    @PostMapping("/batch")
    public ApiResponse<RequestMetricAcceptedDto> record(
            @Valid @RequestBody RequestMetricBatchRequest request, HttpServletRequest http) {
        int accepted = metrics.record(currentMember.idOr(http, "wg"), request.metrics());
        return ApiResponse.ok(new RequestMetricAcceptedDto(accepted));
    }

    /** 查询当前成员最近若干小时的指标聚合和明细。 */
    @GetMapping("/overview")
    public ApiResponse<RequestMetricOverviewDto> overview(
            @RequestParam(defaultValue = "24") int hours, HttpServletRequest http) {
        return ApiResponse.ok(metrics.overview(currentMember.idOr(http, "wg"), hours));
    }

    /** 清空当前成员的全部请求体验历史，其他成员记录保持不变。 */
    @DeleteMapping
    public ApiResponse<RequestMetricClearedDto> clear(HttpServletRequest http) {
        return ApiResponse.ok(new RequestMetricClearedDto(metrics.clear(currentMember.idOr(http, "wg"))));
    }
}
