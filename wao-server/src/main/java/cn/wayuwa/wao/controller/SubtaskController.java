package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.*;
import cn.wayuwa.wao.service.SubtaskMoveService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 子任务关系调整入口，身份始终来自当前会话。 */
@RestController
@RequestMapping("/api/tasks")
public class SubtaskController {
    private final SubtaskMoveService service;
    private final CurrentMember member;
    public SubtaskController(SubtaskMoveService service, CurrentMember member) { this.service = service; this.member = member; }
    /** 原子调整父子关系或所属主任务。 */
    @PostMapping("/{id}/subs/{subId}/move")
    public ApiResponse<List<TaskDto>> move(@PathVariable String id, @PathVariable String subId,
            @Valid @RequestBody MoveSubtaskRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.move(id, subId, request, member.id(http)));
    }

    /** 将内嵌子事项及其后代原子提升为同项目的主任务。 */
    @PostMapping("/{id}/subs/{subId}/promote")
    public ApiResponse<List<TaskDto>> promote(@PathVariable String id, @PathVariable String subId,
            @Valid @RequestBody PromoteSubtaskRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.promote(id, subId, request, member.id(http)));
    }
}
