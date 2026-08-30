package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.GroupNotificationDecisionDto;
import cn.wayuwa.wao.service.NotificationSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Jenkins 等本机发送端只读取最终门禁结论，不读取成员偏好或系统策略明细。 */
@RestController
@RequestMapping("/api/public/notification-settings")
public class PublicNotificationSettingsController {
    private final NotificationSettingsService service;

    public PublicNotificationSettingsController(NotificationSettingsService service) {
        this.service = service;
    }

    @GetMapping("/jenkins-failure")
    public ApiResponse<GroupNotificationDecisionDto> jenkinsFailure() {
        return ApiResponse.ok(service.deliveryFailureDecision());
    }
}
