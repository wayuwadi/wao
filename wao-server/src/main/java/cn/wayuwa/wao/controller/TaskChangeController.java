package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.service.TaskChangeStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class TaskChangeController {
    private final CurrentMember member;
    private final TaskChangeStream stream;
    public TaskChangeController(CurrentMember member, TaskChangeStream stream) {
        this.member = member; this.stream = stream;
    }
    @GetMapping(value = "/api/task-changes", produces = "text/event-stream")
    public SseEmitter subscribe(HttpServletRequest request, HttpServletResponse response) {
        String id = member.id(request);
        if (id == null || id.isBlank()) throw new UnauthorizedException("请先登录");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        return stream.subscribe(id);
    }
}
