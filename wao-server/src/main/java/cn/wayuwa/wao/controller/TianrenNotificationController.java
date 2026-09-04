package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.TianrenNotificationDto;
import cn.wayuwa.wao.service.TianrenNotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** 当前成员自己的群投递状态与重试入口。 */
@RestController
@RequestMapping("/api/tianren/readings/{id}/notification")
public class TianrenNotificationController {
    private final CurrentMember member;
    private final TianrenNotificationService notifications;

    public TianrenNotificationController(CurrentMember member, TianrenNotificationService notifications) {
        this.member = member;
        this.notifications = notifications;
    }

    @GetMapping
    public ApiResponse<TianrenNotificationDto> status(@PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(notifications.status(member.idOr(http, "wg"), id));
    }

    @PostMapping("/retry")
    public ApiResponse<TianrenNotificationDto> retry(@PathVariable String id,
            @RequestBody RetryRequest request, HttpServletRequest http) {
        return ApiResponse.ok(notifications.retry(member.idOr(http, "wg"), id, request.confirmNotReceived()));
    }

    public record RetryRequest(boolean confirmNotReceived) { }
}
