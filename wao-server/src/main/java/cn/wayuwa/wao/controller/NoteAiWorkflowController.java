package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.service.NoteAiWorkflowService;
import cn.wayuwa.wao.service.NoteAiWorkflowService.Status;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前成员的小记后台整理进度与失败重试。 */
@RestController
@RequestMapping("/api/docs/notes/ai-workflows")
public class NoteAiWorkflowController {
    private final NoteAiWorkflowService workflows;
    private final CurrentMember currentMember;

    public NoteAiWorkflowController(NoteAiWorkflowService workflows, CurrentMember currentMember) {
        this.workflows = workflows; this.currentMember = currentMember;
    }

    @GetMapping
    public ApiResponse<List<Status>> list(HttpServletRequest http) {
        return ApiResponse.ok(workflows.list(member(http)));
    }

    @PostMapping("/{docId}/retry")
    public ApiResponse<Status> retry(@PathVariable String docId, HttpServletRequest http) {
        return ApiResponse.ok(workflows.retry(member(http), docId));
    }

    private String member(HttpServletRequest http) {
        String id = currentMember.id(http);
        if (id == null) throw new UnauthorizedException("请先登录后再查看小记自动整理");
        return id;
    }
}
