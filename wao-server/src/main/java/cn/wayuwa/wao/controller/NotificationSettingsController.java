package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.NotificationSettingsDto;
import cn.wayuwa.wao.dto.SaveNotificationSettingsRequest;
import cn.wayuwa.wao.service.NotificationSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 通知设置：系统策略由管理员共享维护，站内偏好始终按当前成员隔离。 */
@RestController
@RequestMapping("/api/notification-settings")
public class NotificationSettingsController {
    private final NotificationSettingsService service;
    private final CurrentMember currentMember;

    public NotificationSettingsController(NotificationSettingsService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    @GetMapping
    public ApiResponse<NotificationSettingsDto> get(HttpServletRequest request) {
        return ApiResponse.ok(service.get(currentMember.id(request)));
    }

    @PutMapping
    public ApiResponse<NotificationSettingsDto> save(@RequestBody SaveNotificationSettingsRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(service.save(currentMember.id(http), request));
    }
}
