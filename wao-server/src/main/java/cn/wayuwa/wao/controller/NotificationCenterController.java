package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.NotificationCenterDto;
import cn.wayuwa.wao.service.NotificationCenterService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 应用壳右上角通知中心接口。 */
@RestController
@RequestMapping("/api/notification-center")
public class NotificationCenterController {
    private final NotificationCenterService service;
    private final CurrentMember currentMember;

    public NotificationCenterController(NotificationCenterService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    @GetMapping
    public ApiResponse<NotificationCenterDto> get(HttpServletRequest request) {
        return ApiResponse.ok(service.get(member(request), "true".equals(request.getParameter("includeInternal"))));
    }

    @PutMapping("/read")
    public ApiResponse<OffsetDateTime> markRead(HttpServletRequest request) {
        return ApiResponse.ok(service.markRead(member(request)));
    }

    private String member(HttpServletRequest request) {
        String memberId = currentMember.id(request);
        if (!StringUtils.hasText(memberId)) throw new UnauthorizedException("登录成员不存在，请重新登录");
        return memberId;
    }
}
