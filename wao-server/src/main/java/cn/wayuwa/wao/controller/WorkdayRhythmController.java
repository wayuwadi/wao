package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.EnergyNotificationResultDto;
import cn.wayuwa.wao.dto.SendEnergyNotificationRequest;
import cn.wayuwa.wao.dto.WorkdayRhythmHistoryDto;
import cn.wayuwa.wao.service.WorkdayRhythmHistoryService;
import cn.wayuwa.wao.service.WorkdayRhythmNotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 今日节奏需要服务端代发的成员级动作。 */
@RestController
@RequestMapping("/api/workday-rhythm")
public class WorkdayRhythmController {

    private static final String FALLBACK_OWNER = "wg";

    private final WorkdayRhythmNotificationService notifications;
    private final WorkdayRhythmHistoryService history;
    private final CurrentMember currentMember;

    public WorkdayRhythmController(WorkdayRhythmNotificationService notifications,
            WorkdayRhythmHistoryService history, CurrentMember currentMember) {
        this.notifications = notifications;
        this.history = history;
        this.currentMember = currentMember;
    }

    @GetMapping("/history")
    public ApiResponse<WorkdayRhythmHistoryDto> history(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest http) {
        return ApiResponse.ok(history.history(currentMember.idOr(http, FALLBACK_OWNER), from, to));
    }

    @PostMapping("/energy-notifications")
    public ApiResponse<EnergyNotificationResultDto> notifyEnergy(
            @Valid @RequestBody SendEnergyNotificationRequest request, HttpServletRequest http) {
        return ApiResponse.ok(notifications.notifyEnergy(currentMember.idOr(http, FALLBACK_OWNER), request));
    }
}
