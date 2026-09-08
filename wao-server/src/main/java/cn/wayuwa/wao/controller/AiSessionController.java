package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.*;
import cn.wayuwa.wao.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import java.util.List;

/** 云端会话入口，所有会话读写均使用登录成员的项目权限。 */
@RestController
@RequestMapping("/api/ai/sessions")
public class AiSessionController {
    private final AiSessionService sessions;
    private final AiRunService runs;
    private final ProjectService projects;
    private final CurrentMember members;
    public AiSessionController(AiSessionService sessions, AiRunService runs, ProjectService projects, CurrentMember members) {
        this.sessions=sessions; this.runs=runs; this.projects=projects; this.members=members;
    }
    @GetMapping
    public ApiResponse<AiSessionService.Page> list(HttpServletRequest http,
            @RequestParam(defaultValue="") String q, @RequestParam(defaultValue="") String status,
            @RequestParam(defaultValue="") String engine, @RequestParam(defaultValue="false") boolean archived,
            @RequestParam(defaultValue="1") int page) {
        return ApiResponse.ok(sessions.list(members.id(http),q,status,engine,archived,page));
    }
    @GetMapping("/workflows")
    public ApiResponse<List<AiWorkflowDto>> workflows(HttpServletRequest http) {
        var visible=projects.visibleProjectIds(members.id(http));
        return ApiResponse.ok(runs.listWorkflows().stream().filter(w -> visible.contains(w.projectId()) && w.configured() && !w.projectDeleted()).toList());
    }
    @PostMapping
    public ApiResponse<AiRunDto> create(@Valid @RequestBody LaunchAutonomousGoalRequest req, HttpServletRequest http) {
        String member=members.id(http); projects.get(req.projectId(),member);
        return ApiResponse.ok(runs.launchGoal(req.projectId(),req.goal(),member,
                ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString(),
                req.runnerId(),null,req.planMode(),req.goalMode(),req.reasoningEffort()));
    }
    @GetMapping("/{id}")
    public ApiResponse<AiSessionService.Detail> detail(@PathVariable String id,HttpServletRequest http) {
        return ApiResponse.ok(sessions.detail(members.id(http),id));
    }
    @PutMapping("/{id}/preference")
    public ApiResponse<AiSessionPreference> save(@PathVariable String id,@Valid @RequestBody AiSessionPreference req,HttpServletRequest http) {
        return ApiResponse.ok(sessions.save(members.id(http),id,req));
    }
    @GetMapping("/{id}/conversation")
    public ApiResponse<AiRunProgressPageDto> conversation(@PathVariable String id,
            @RequestParam(required=false) String before,HttpServletRequest http) {
        sessions.requireVisible(members.id(http),id); return ApiResponse.ok(runs.conversation(id,before));
    }
    @PostMapping("/{id}/continue")
    public ApiResponse<AiRunDto> resume(@PathVariable String id,@Valid @RequestBody ContinueAiRunRequest req,HttpServletRequest http) {
        String member=members.id(http); sessions.requireVisible(member,id);
        return ApiResponse.ok(runs.continueRun(id,req.input(),member,ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString()));
    }
    @PostMapping("/{id}/pause")
    public ApiResponse<AiRunDto> pause(@PathVariable String id,HttpServletRequest http) {
        sessions.requireVisible(members.id(http),id); return ApiResponse.ok(runs.pause(id));
    }
}
