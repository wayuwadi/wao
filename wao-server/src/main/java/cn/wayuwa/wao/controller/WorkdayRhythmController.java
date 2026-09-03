package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.EnergyNotificationResultDto;
import cn.wayuwa.wao.dto.SendEnergyNotificationRequest;
import cn.wayuwa.wao.dto.SendFocusNotificationRequest;
import cn.wayuwa.wao.dto.CreateWorkdayRhythmDocumentRequest;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.SaveWorkdayRhythmChecklistItemRequest;
import cn.wayuwa.wao.dto.SaveWorkdayRhythmFocusRequest;
import cn.wayuwa.wao.dto.SaveWorkdayRhythmSettingRequest;
import cn.wayuwa.wao.dto.WorkdayRhythmChecklistItemDto;
import cn.wayuwa.wao.dto.WorkdayRhythmCompletionRequest;
import cn.wayuwa.wao.dto.WorkdayRhythmDashboardDto;
import cn.wayuwa.wao.dto.WorkdayRhythmHistoryDto;
import cn.wayuwa.wao.dto.WorkdayRhythmStatusRequest;
import cn.wayuwa.wao.dto.WorkdayRhythmTimerRequest;
import cn.wayuwa.wao.service.WorkdayRhythmDashboardService;
import cn.wayuwa.wao.service.WorkdayRhythmHistoryService;
import cn.wayuwa.wao.service.WorkdayRhythmNotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final WorkdayRhythmDashboardService dashboard;
    private final CurrentMember currentMember;

    public WorkdayRhythmController(WorkdayRhythmNotificationService notifications,
            WorkdayRhythmHistoryService history, WorkdayRhythmDashboardService dashboard,
            CurrentMember currentMember) {
        this.notifications = notifications;
        this.history = history;
        this.dashboard = dashboard;
        this.currentMember = currentMember;
    }

    @GetMapping("/dashboard")
    public ApiResponse<WorkdayRhythmDashboardDto> dashboard(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            HttpServletRequest http) {
        return ApiResponse.ok(dashboard.dashboard(member(http), date));
    }

    @GetMapping("/settings")
    public ApiResponse<WorkdayRhythmDashboardDto.Settings> settings(HttpServletRequest http) {
        return ApiResponse.ok(dashboard.dashboard(member(http), LocalDate.now(ZoneId.of("Asia/Shanghai"))).settings());
    }

    @PutMapping("/settings")
    public ApiResponse<WorkdayRhythmDashboardDto.Settings> saveSettings(
            @RequestBody SaveWorkdayRhythmSettingRequest request, HttpServletRequest http) {
        return ApiResponse.ok(dashboard.saveSettings(member(http), request));
    }

    @GetMapping("/checklist-items")
    public ApiResponse<List<WorkdayRhythmChecklistItemDto>> checklistItems(HttpServletRequest http) {
        return ApiResponse.ok(dashboard.checklistItems(member(http)));
    }

    @PostMapping("/checklist-items")
    public ApiResponse<WorkdayRhythmChecklistItemDto> createChecklistItem(
            @RequestBody SaveWorkdayRhythmChecklistItemRequest request, HttpServletRequest http) {
        return ApiResponse.ok(dashboard.createItem(member(http), request));
    }

    @PatchMapping("/checklist-items/{id}")
    public ApiResponse<WorkdayRhythmChecklistItemDto> updateChecklistItem(@PathVariable String id,
            @RequestBody SaveWorkdayRhythmChecklistItemRequest request, HttpServletRequest http) {
        return ApiResponse.ok(dashboard.updateItem(member(http), id, request));
    }

    @DeleteMapping("/checklist-items/{id}")
    public ApiResponse<Void> deleteChecklistItem(@PathVariable String id, HttpServletRequest http) {
        dashboard.deleteItem(member(http), id);
        return ApiResponse.ok(null);
    }

    @PutMapping("/days/{date}/workday-status")
    public ApiResponse<WorkdayRhythmDashboardDto> workdayStatus(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody WorkdayRhythmStatusRequest request, HttpServletRequest http) {
        return ApiResponse.ok(dashboard.setWorkdayOverride(member(http), date, request.active()));
    }

    @PutMapping("/days/{date}/focus")
    public ApiResponse<WorkdayRhythmDashboardDto> focus(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody SaveWorkdayRhythmFocusRequest request, HttpServletRequest http) {
        return ApiResponse.ok(dashboard.saveFocus(member(http), date, request));
    }

    @PutMapping("/occurrences/{id}/completion")
    public ApiResponse<WorkdayRhythmDashboardDto.Occurrence> completion(@PathVariable String id,
            @RequestBody WorkdayRhythmCompletionRequest request, HttpServletRequest http) {
        return ApiResponse.ok(dashboard.setCompleted(member(http), id, request.completed()));
    }

    @PutMapping("/occurrences/{id}/timer")
    public ApiResponse<WorkdayRhythmDashboardDto.Occurrence> timer(@PathVariable String id,
            @RequestBody WorkdayRhythmTimerRequest request, HttpServletRequest http) {
        return ApiResponse.ok(dashboard.updateTimer(member(http), id, request.action()));
    }

    @PutMapping("/days/{date}/document")
    public ApiResponse<DocDto> document(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody CreateWorkdayRhythmDocumentRequest request, HttpServletRequest http) {
        return ApiResponse.ok(dashboard.createDocument(member(http), date, request));
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

    @PostMapping("/focus-notifications")
    public ApiResponse<EnergyNotificationResultDto> notifyFocus(
            @Valid @RequestBody SendFocusNotificationRequest request, HttpServletRequest http) {
        return ApiResponse.ok(notifications.notifyFocus(currentMember.idOr(http, FALLBACK_OWNER), request.date()));
    }

    private String member(HttpServletRequest http) {
        return currentMember.idOr(http, FALLBACK_OWNER);
    }
}
