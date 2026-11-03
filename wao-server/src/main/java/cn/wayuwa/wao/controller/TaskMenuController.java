package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.*;
import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.service.TaskMenuService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tasks/{taskId}/menu-operations/{operation}")
public class TaskMenuController {
    private final TaskMenuService service; private final AuthSessionManager sessions; private final CurrentMember members;
    public TaskMenuController(TaskMenuService service,AuthSessionManager sessions,CurrentMember members){this.service=service;this.sessions=sessions;this.members=members;}
    public record Request(String accountId,TaskMenuService.Request change) {}
    @PutMapping public ApiResponse<TaskMenuService.Result> apply(@PathVariable String taskId,@PathVariable String operation,@RequestBody Request body,HttpServletRequest request){
        String account=sessions.currentAccountId(request);
        if(account==null || !account.equals(body.accountId()))throw new UnauthorizedException("登录账号已变化，请在原账号下重试");
        return ApiResponse.ok(service.apply(account,members.id(request),taskId,operation,body.change()));
    }
}
