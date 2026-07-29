package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.AutomationJobDto;
import cn.wayuwa.wao.dto.CreateJobRequest;
import cn.wayuwa.wao.dto.PatchJobRequest;
import cn.wayuwa.wao.service.AutomationService;
import cn.wayuwa.wao.auth.CurrentMember;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 自动化任务接口。 */
@RestController
@RequestMapping("/api/jobs")
public class AutomationController {

    private final AutomationService automationService;
    private final CurrentMember currentMember;
    public AutomationController(AutomationService automationService, CurrentMember currentMember) {
        this.automationService = automationService;
        this.currentMember = currentMember;
    }

    @GetMapping
    public ApiResponse<List<AutomationJobDto>> list() {
        return ApiResponse.ok(automationService.listJobs());
    }

    @GetMapping("/{id}")
    public ApiResponse<AutomationJobDto> get(@PathVariable String id) {
        return ApiResponse.ok(automationService.getJob(id));
    }

    @PostMapping
    public ApiResponse<AutomationJobDto> create(@Valid @RequestBody CreateJobRequest request) {
        return ApiResponse.ok(automationService.createJob(request));
    }

    @PatchMapping("/{id}")
    public ApiResponse<AutomationJobDto> patch(@PathVariable String id, @RequestBody PatchJobRequest request) {
        return ApiResponse.ok(automationService.updateJob(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        automationService.deleteJob(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/run")
    public ApiResponse<AutomationJobDto> run(@PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(automationService.runJob(id, currentMember.idOr(http, "wg")));
    }

    @PostMapping("/{id}/pause")
    public ApiResponse<AutomationJobDto> pause(@PathVariable String id) {
        return ApiResponse.ok(automationService.pauseJob(id));
    }

    @PostMapping("/{id}/resume")
    public ApiResponse<AutomationJobDto> resume(@PathVariable String id) {
        return ApiResponse.ok(automationService.resumeJob(id));
    }

    @PostMapping("/{id}/reauth")
    public ApiResponse<AutomationJobDto> reauth(@PathVariable String id) {
        return ApiResponse.ok(automationService.reauthJob(id));
    }
}
