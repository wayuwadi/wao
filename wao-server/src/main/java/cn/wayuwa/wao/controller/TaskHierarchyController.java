package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.service.TaskHierarchyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** 完整任务归属调整；普通 PUT 忽略 parentTaskId。 */
@RestController
@RequestMapping("/api/tasks")
public class TaskHierarchyController {
    private final TaskHierarchyService service;
    private final CurrentMember member;
    public TaskHierarchyController(TaskHierarchyService service, CurrentMember member) { this.service = service; this.member = member; }
    public record MoveRequest(String parentTaskId, String expectedParentTaskId) {}
    /** 将任务放入另一任务，或移回顶层。 */
    @PostMapping("/{id}/parent")
    public ApiResponse<TaskDto> move(@PathVariable String id, @RequestBody MoveRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.move(id, request.parentTaskId(), request.expectedParentTaskId(), member.id(http)));
    }
}
