package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.service.HomeDateReminderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 首页日期提醒的本人读取、条件设置与可见后回执，不对工作区令牌扩权。 */
@RestController
@RequestMapping("/api/home/date-reminders")
public class HomeDateReminderController {
    private final HomeDateReminderService service;
    private final CurrentMember members;
    public record Presentation(@NotBlank @Size(max=10) String date,
            @jakarta.validation.constraints.NotNull @Size(min=1,max=8) List<@NotBlank @Size(max=128) String> keys,
            @Size(max=36) String token, @Size(max=16) String state) { }
    public HomeDateReminderController(HomeDateReminderService service, CurrentMember members) {
        this.service = service; this.members = members;
    }
    /** 读取本人今天的候选，查询无展示副作用。 */
    @GetMapping public ApiResponse<HomeDateReminderService.Queue> queue(HttpServletRequest request) {
        return ApiResponse.ok(service.queue(members.id(request)));
    }
    /** 原子争取短暂展示租约，不把尚未显示的提醒记作已读。 */
    @PostMapping("/reserve") public ApiResponse<HomeDateReminderService.Reservation> reserve(
            @Valid @RequestBody Presentation value, HttpServletRequest request) {
        return ApiResponse.ok(service.reserve(members.id(request), value.date(), value.keys()));
    }
    /** 保存实际展示、确认或释放未展示租约的回执。 */
    @PostMapping("/receipt") public ApiResponse<Void> receipt(
            @Valid @RequestBody Presentation value, HttpServletRequest request) {
        if (value.token() == null || value.token().isBlank() || value.state() == null)
            throw new cn.wayuwa.wao.common.BadRequestException("请提供展示回执");
        service.record(members.id(request), value.date(), value.keys(), value.token(), value.state());
        return ApiResponse.ok(null);
    }
    /** 读取本人首页提醒渠道偏好。 */
    @GetMapping("/settings") public ApiResponse<HomeDateReminderService.Settings> settings(HttpServletRequest request) {
        return ApiResponse.ok(service.settings(members.id(request)));
    }
    /** 按版本保存本人首页提醒渠道偏好。 */
    @PutMapping("/settings") public ApiResponse<HomeDateReminderService.Settings> save(
            @RequestBody HomeDateReminderService.Settings value, HttpServletRequest request) {
        return ApiResponse.ok(service.saveSettings(members.id(request), value));
    }
}
