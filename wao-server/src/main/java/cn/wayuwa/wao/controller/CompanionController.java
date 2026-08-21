package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.auth.AuthSessionManager;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.CompanionActivitySnapshotDto;
import cn.wayuwa.wao.dto.CompanionDeviceDto;
import cn.wayuwa.wao.dto.CompanionPairingCreatedDto;
import cn.wayuwa.wao.dto.CompanionPairingPollDto;
import cn.wayuwa.wao.dto.CompanionPairingPreviewDto;
import cn.wayuwa.wao.dto.CreateCompanionPairingRequest;
import cn.wayuwa.wao.dto.PollCompanionPairingRequest;
import cn.wayuwa.wao.service.CompanionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Windows 桌宠配对、设备管理与最小权限活动接口。 */
@RestController
@RequestMapping("/api")
public class CompanionController {
    private final CompanionService service;
    private final AuthSessionManager sessions;
    private final CurrentMember currentMember;

    public CompanionController(CompanionService service, AuthSessionManager sessions, CurrentMember currentMember) {
        this.service = service;
        this.sessions = sessions;
        this.currentMember = currentMember;
    }

    @PostMapping("/public/companion/pairings")
    public ApiResponse<CompanionPairingCreatedDto> createPairing(
            @Valid @RequestBody CreateCompanionPairingRequest request) {
        return ApiResponse.ok(service.createPairing(request, webBaseUrl()));
    }

    @PostMapping("/public/companion/pairings/{id}/poll")
    public ApiResponse<CompanionPairingPollDto> poll(@PathVariable String id,
            @Valid @RequestBody PollCompanionPairingRequest request) {
        return ApiResponse.ok(service.poll(id, request.pollSecret()));
    }

    @GetMapping("/companion/pairings/{code}")
    public ApiResponse<CompanionPairingPreviewDto> preview(@PathVariable String code) {
        return ApiResponse.ok(service.preview(code));
    }

    @PostMapping("/companion/pairings/{code}")
    public ApiResponse<CompanionPairingPreviewDto> approve(@PathVariable String code, HttpServletRequest request) {
        return ApiResponse.ok(service.approve(code, sessions.currentAccountId(request), currentMember.id(request)));
    }

    @GetMapping("/companion/devices")
    public ApiResponse<List<CompanionDeviceDto>> devices(HttpServletRequest request) {
        return ApiResponse.ok(service.listDevices(sessions.currentAccountId(request)));
    }

    @DeleteMapping("/companion/devices/{id}")
    public ApiResponse<Void> revoke(@PathVariable String id, HttpServletRequest request) {
        service.revokeDevice(id, sessions.currentAccountId(request));
        return ApiResponse.ok(null);
    }

    @GetMapping("/public/companion/activities")
    public ApiResponse<CompanionActivitySnapshotDto> activities(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.ok(service.activities(authorization, webBaseUrl()));
    }

    @PostMapping("/public/companion/activities/{id}/read")
    public ApiResponse<Void> read(@PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        service.readActivity(authorization, id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/public/companion/activities/read-all")
    public ApiResponse<Void> readAll(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        service.readAll(authorization);
        return ApiResponse.ok(null);
    }

    private String webBaseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }
}
