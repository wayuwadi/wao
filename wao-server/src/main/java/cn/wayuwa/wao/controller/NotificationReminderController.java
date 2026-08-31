package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.NotificationReminderDto;
import cn.wayuwa.wao.dto.NotificationReminderSettingsDto;
import cn.wayuwa.wao.dto.SaveNotificationReminderRequest;
import cn.wayuwa.wao.service.NotificationReminderService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 通知设置中的每日飞书群提醒接口。 */
@RestController
@RequestMapping("/api/notification-reminders")
public class NotificationReminderController {
    private final NotificationReminderService service;
    private final CurrentMember currentMember;

    public NotificationReminderController(NotificationReminderService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    @GetMapping
    public ApiResponse<NotificationReminderSettingsDto> get(HttpServletRequest request) {
        return ApiResponse.ok(service.get(currentMember.id(request)));
    }

    @PostMapping
    public ApiResponse<NotificationReminderDto> create(@RequestBody SaveNotificationReminderRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(service.create(currentMember.id(http), request));
    }

    @PutMapping("/{id}")
    public ApiResponse<NotificationReminderDto> update(@PathVariable String id,
            @RequestBody SaveNotificationReminderRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.update(currentMember.id(http), id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest http) {
        service.delete(currentMember.id(http), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/test")
    public ApiResponse<NotificationReminderDto> test(@PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(service.test(currentMember.id(http), id));
    }
}
