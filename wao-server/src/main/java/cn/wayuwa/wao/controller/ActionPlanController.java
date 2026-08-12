package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.ActionPlanDto;
import cn.wayuwa.wao.dto.SaveActionNoteRequest;
import cn.wayuwa.wao.dto.SaveActionPlanRequest;
import cn.wayuwa.wao.service.ActionPlanService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 当前成员的行动计划、打卡和日复盘接口。 */
@RestController
@RequestMapping("/api/action-plans")
public class ActionPlanController {
    private static final String FALLBACK_MEMBER = "wg";
    private final ActionPlanService service;
    private final CurrentMember currentMember;

    public ActionPlanController(ActionPlanService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    /** 按日期读取当前成员全部行动计划。 */
    @GetMapping
    public ApiResponse<List<ActionPlanDto>> list(@RequestParam(required = false) LocalDate date,
            HttpServletRequest http) {
        return ApiResponse.ok(service.list(member(http), date));
    }

    /** 新建一个由当前成员自行定义行动内容的计划。 */
    @PostMapping
    public ApiResponse<ActionPlanDto> create(@Valid @RequestBody SaveActionPlanRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(service.create(member(http), request));
    }

    /** 保存计划设置和当前行动版本。 */
    @PutMapping("/{planId}")
    public ApiResponse<ActionPlanDto> update(@PathVariable String planId,
            @Valid @RequestBody SaveActionPlanRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.update(planId, member(http), request));
    }

    /** 完成或重新开始计划；历史打卡始终保留。 */
    @PatchMapping("/{planId}/status")
    public ApiResponse<ActionPlanDto> status(@PathVariable String planId,
            @RequestParam boolean completed, HttpServletRequest http) {
        return ApiResponse.ok(service.setCompleted(planId, member(http), completed));
    }

    /** 切换某天一条行动的完成状态。 */
    @PostMapping("/{planId}/items/{itemId}/checkins/{date}")
    public ApiResponse<ActionPlanDto> toggleCheckin(@PathVariable String planId, @PathVariable String itemId,
            @PathVariable LocalDate date, HttpServletRequest http) {
        return ApiResponse.ok(service.toggleCheckin(planId, itemId, member(http), date));
    }

    /** 自动保存或清空某天事实复盘。 */
    @PutMapping("/{planId}/notes/{date}")
    public ApiResponse<ActionPlanDto> saveNote(@PathVariable String planId, @PathVariable LocalDate date,
            @Valid @RequestBody SaveActionNoteRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.saveNote(planId, member(http), date, request.content()));
    }

    private String member(HttpServletRequest http) {
        return currentMember.idOr(http, FALLBACK_MEMBER);
    }
}
