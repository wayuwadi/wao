package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.service.TaskTimeRule;
import cn.wayuwa.wao.service.TaskTimeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/task-time")
public class TaskTimeController {
    private final TaskTimeService time;private final CurrentMember member;
    public TaskTimeController(TaskTimeService time,CurrentMember member){this.time=time;this.member=member;}
    @GetMapping("/{id}") public ApiResponse<TaskTimeService.State> get(@PathVariable String id,HttpServletRequest r){return ApiResponse.ok(time.get(id,member.id(r)));}
    @PutMapping("/{id}") public ApiResponse<TaskTimeService.State> save(@PathVariable String id,@RequestBody TaskTimeRule rule,HttpServletRequest r){return ApiResponse.ok(time.save(id,member.id(r),rule));}
    @PostMapping("/{id}/skip") public ApiResponse<TaskTimeService.State> skip(@PathVariable String id,@RequestBody TaskTimeService.Advance request,HttpServletRequest r){return ApiResponse.ok(time.skip(id,member.id(r),request));}
    @PostMapping("/{id}/retry") public ApiResponse<TaskTimeService.State> retry(@PathVariable String id,HttpServletRequest r){return ApiResponse.ok(time.retry(id,member.id(r)));}
    @PostMapping("/{id}/test/{operation}") public ApiResponse<TaskTimeService.State> test(@PathVariable String id,@PathVariable String operation,HttpServletRequest r){return ApiResponse.ok(time.test(id,member.id(r),operation));}
}
