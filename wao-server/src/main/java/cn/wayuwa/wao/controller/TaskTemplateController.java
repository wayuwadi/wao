package cn.wayuwa.wao.controller;

import java.util.List;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.dto.TaskTemplateWriteRequest;
import cn.wayuwa.wao.dto.TaskTemplateCreateRequest;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.service.TaskTemplateService;
import cn.wayuwa.wao.service.TaskTemplateCreationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/task-templates")
public class TaskTemplateController {
    private final TaskTemplateService templates;
    private final TaskTemplateCreationService creation;
    private final CurrentMember member;
    public TaskTemplateController(TaskTemplateService templates, TaskTemplateCreationService creation, CurrentMember member) { this.templates = templates; this.creation = creation; this.member = member; }
    private String requireMember(HttpServletRequest request) {
        String id = member.id(request);
        if (id == null || id.isBlank()) throw new UnauthorizedException("登录已过期，请重新登录");
        return id;
    }
    private void requireAccount(HttpServletRequest request, String accountId) {
        if (accountId == null || !accountId.equals(member.accountId(request))) throw new ConflictException("账号已切换，请重新打开模板");
    }

    @GetMapping
    public ApiResponse<List<TaskTemplateService.Template>> list(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        return ApiResponse.ok(templates.list(requireMember(request)));
    }
    @PutMapping("/{id}")
    public ApiResponse<TaskTemplateService.Template> save(@PathVariable String id, @Valid @RequestBody TaskTemplateWriteRequest write, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        String current = requireMember(request);
        requireAccount(request, write.accountId());
        return ApiResponse.ok(templates.save(id, current, write.content()));
    }
    @PutMapping("/creations/{taskId}")
    public ApiResponse<TaskDto> createTask(@PathVariable String taskId, @Valid @RequestBody TaskTemplateCreateRequest write, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        String current = requireMember(request);
        requireAccount(request, write.accountId());
        return ApiResponse.ok(creation.create(taskId, current, write));
    }
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, @RequestHeader("X-WAO-Account-Id") String accountId, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        String current = requireMember(request);
        requireAccount(request, accountId);
        templates.delete(id, current);
        return ApiResponse.ok(null);
    }
}
