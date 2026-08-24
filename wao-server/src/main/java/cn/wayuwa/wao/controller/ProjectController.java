package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.dto.CreateProjectRequest;
import cn.wayuwa.wao.dto.PatchProjectRequest;
import cn.wayuwa.wao.dto.ProjectDto;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.service.KnowledgeService;
import cn.wayuwa.wao.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

/** 项目接口。 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final CurrentMember currentMember;
    private final KnowledgeService knowledgeService;

    public ProjectController(ProjectService projectService, CurrentMember currentMember,
            KnowledgeService knowledgeService) {
        this.projectService = projectService;
        this.currentMember = currentMember;
        this.knowledgeService = knowledgeService;
    }

    /** 首次明确开始推进；重复调用不会重复发通知。 */
    @PostMapping("/{id}/start")
    public ApiResponse<ProjectDto> start(@PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(projectService.start(id, currentMember.idOr(http, "wg")));
    }

    /** 幂等创建或返回项目说明文档；不预建空知识库。 */
    @PostMapping("/{id}/brief-doc")
    public ApiResponse<DocDto> briefDoc(@PathVariable String id, HttpServletRequest http) {
        String memberId = currentMember.idOr(http, "wg");
        return ApiResponse.ok(knowledgeService.ensureProjectBrief(projectService.get(id, memberId), memberId));
    }

    @GetMapping
    public ApiResponse<List<ProjectDto>> list(HttpServletRequest http) {
        return ApiResponse.ok(projectService.list(currentMember.idOr(http, "wg")));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProjectDto> get(@PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(projectService.get(id, currentMember.idOr(http, "wg")));
    }

    @PostMapping
    public ApiResponse<ProjectDto> create(@Valid @RequestBody CreateProjectRequest request, HttpServletRequest http) {
        return ApiResponse.ok(projectService.create(request, currentMember.idOr(http, "wg")));
    }

    @PatchMapping("/{id}")
    public ApiResponse<ProjectDto> patch(@PathVariable String id, @RequestBody PatchProjectRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(projectService.update(id, request, currentMember.idOr(http, "wg")));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest http) {
        projectService.delete(id, currentMember.idOr(http, "wg"));
        return ApiResponse.ok(null);
    }
}
