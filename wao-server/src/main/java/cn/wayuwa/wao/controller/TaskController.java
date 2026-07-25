package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.dto.CommentDto;
import cn.wayuwa.wao.dto.CreateCommentRequest;
import cn.wayuwa.wao.dto.CreateTaskRequest;
import cn.wayuwa.wao.dto.PatchTaskRequest;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

/** 任务接口。 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final CurrentMember currentMember;

    public TaskController(TaskService taskService, CurrentMember currentMember) {
        this.taskService = taskService;
        this.currentMember = currentMember;
    }

    @GetMapping
    public ApiResponse<List<TaskDto>> list() {
        return ApiResponse.ok(taskService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<TaskDto> get(@PathVariable String id) {
        return ApiResponse.ok(taskService.get(id));
    }

    @PostMapping
    public ApiResponse<TaskDto> create(@Valid @RequestBody CreateTaskRequest request) {
        return ApiResponse.ok(taskService.create(request));
    }

    @PatchMapping("/{id}")
    public ApiResponse<TaskDto> patch(@PathVariable String id, @RequestBody PatchTaskRequest request) {
        return ApiResponse.ok(taskService.patch(id, request));
    }

    @PutMapping("/{id}")
    public ApiResponse<TaskDto> replace(@PathVariable String id, @RequestBody TaskDto request) {
        return ApiResponse.ok(taskService.replace(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        taskService.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/comments")
    public ApiResponse<CommentDto> addComment(@PathVariable String id, @Valid @RequestBody CreateCommentRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(taskService.addComment(id, currentMember.idOr(http, "unknown"), request.text()));
    }

    /** 改自己的评论；改别人的会被拒。 */
    @PatchMapping("/{id}/comments/{commentId}")
    public ApiResponse<CommentDto> updateComment(@PathVariable String id, @PathVariable String commentId,
            @Valid @RequestBody CreateCommentRequest request, HttpServletRequest http) {
        return ApiResponse.ok(taskService.updateComment(id, commentId, currentMember.idOr(http, "unknown"), request.text()));
    }

    /** 删自己的评论；删别人的会被拒。 */
    @DeleteMapping("/{id}/comments/{commentId}")
    public ApiResponse<Void> deleteComment(@PathVariable String id, @PathVariable String commentId,
            HttpServletRequest http) {
        taskService.deleteComment(id, commentId, currentMember.idOr(http, "unknown"));
        return ApiResponse.ok(null);
    }
}
