package cn.wayuwa.wao.controller;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.service.TaskLabelService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/task-labels")
public class TaskLabelController {
 private final TaskLabelService service; private final CurrentMember member;
 public TaskLabelController(TaskLabelService service,CurrentMember member){this.service=service;this.member=member;}
 @GetMapping public ApiResponse<List<TaskLabelService.Labels>> list(HttpServletRequest r){return ApiResponse.ok(service.list(member.id(r)));}
 @PutMapping("/{taskId}") public ApiResponse<TaskLabelService.Labels> save(@PathVariable String taskId,@RequestBody TaskLabelService.Change change,HttpServletRequest r){return ApiResponse.ok(service.save(taskId,change,member.id(r)));}
}
