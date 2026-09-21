package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.AuthSessionManager;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.service.TaskWriteOperationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 任务创建与保存的跨节点操作回执，不接受客户端指定账号。 */
@RestController
@RequestMapping("/api/tasks/{taskId}/operations/{operation}")
public class TaskWriteOperationController {
    public record Request(String accountId, TaskDto task) {}
    private final TaskWriteOperationService operations;
    private final AuthSessionManager sessions;
    private final CurrentMember members;

    public TaskWriteOperationController(TaskWriteOperationService operations, AuthSessionManager sessions, CurrentMember members) {
        this.operations = operations;
        this.sessions = sessions;
        this.members = members;
    }

    @PutMapping
    public ApiResponse<TaskDto> replace(@PathVariable String taskId, @PathVariable String operation,
            @RequestBody Request body, HttpServletRequest request) {
        String account = sessions.currentAccountId(request);
        if (account == null || !account.equals(body.accountId()))
            throw new UnauthorizedException("登录账号已变化，原账号草稿仍保留");
        if (body.task() == null) throw new cn.wayuwa.wao.common.BadRequestException("缺少任务内容");
        return ApiResponse.ok(operations.replace(account, members.id(request), taskId, operation, body.task()));
    }

    @GetMapping
    public ApiResponse<TaskWriteOperationService.Result> find(@PathVariable String taskId, @PathVariable String operation,
            HttpServletRequest request) {
        return ApiResponse.ok(operations.find(sessions.currentAccountId(request), members.id(request), taskId, operation));
    }
}
